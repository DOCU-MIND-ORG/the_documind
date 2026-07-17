package com.accenture.intern.docmind.aiservices.retrieval;

import com.accenture.intern.docmind.entity.DocumentChunk;
import com.accenture.intern.docmind.repository.DocumentChunkRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.*;
import java.util.stream.Collectors;
import com.accenture.intern.docmind.dto.retrieval.RetrievalCandidate;
import com.accenture.intern.docmind.aiservices.understanding.EntityResolution;

/**
 * Hybrid retrieval for DocMind's shared company knowledge base.
 * <p>
 * DocMind is intentionally NOT session-isolated: any document ever uploaded by
 * anyone, in any session, is searchable from every other session. This service
 * runs BM25-style keyword search (Postgres full-text, via
 * DocumentChunkRepository)
 * and dense semantic search (Pinecone, via VectorStoreService) across the
 * *entire*
 * corpus in parallel, then fuses the two ranked lists with Reciprocal Rank
 * Fusion
 * (RRF).
 * <p>
 * On top of plain RRF, chunks that belong to the *current* session get a
 * relevance
 * boost — not a filter. The intuition: if you're actively chatting in a session
 * where you uploaded a doc, a matching chunk from that doc is more likely to be
 * what you mean, so it's worth nudging up the ranking. But it's still a real
 * company-wide search underneath — a strongly relevant chunk from someone
 * else's
 * document in another session can still outrank a weak match from your own
 * session, which is the point of a *soft* boost rather than a hard session
 * filter.
 * <p>
 * Why RRF instead of combining raw scores: BM25/ts_rank scores and Pinecone
 * similarity scores live on completely different, unbounded scales, so
 * averaging
 * or weighting them directly is unreliable and needs constant re-tuning. RRF
 * only
 * looks at each document's *rank* in each list, which makes it scale-free and
 * the
 * de facto standard for combining lexical + dense retrieval (this is the same
 * technique Elasticsearch, Weaviate, and Azure AI Search use for their hybrid
 * search modes). The session boost is applied as a multiplier on top of each
 * document's RRF score, so it composes cleanly with fusion instead of needing
 * its
 * own separate scale.
 * <p>
 * Why fuse before reranking rather than just picking the dense results: keyword
 * search catches exact terms, acronyms, IDs, names, and rare words that an
 * embedding model can blur together (e.g. a product code or a person's name),
 * while
 * dense search catches paraphrases and conceptual matches that share no words
 * with
 * the query. Fusing the two before the cross-encoder rerank means the reranker
 * always gets the best candidates from both worlds rather than only one.
 */
@Slf4j
@Service
public class HybridRetrievalService {

    /**
     * RRF damping constant. 60 is the standard value used in the original RRF paper
     * and in most production hybrid-search implementations; it controls how quickly
     * a document's contribution falls off as its rank gets worse.
     */
    private static final int RRF_K = 60;

    /**
     * How many candidates to pull from each retriever (across the whole company
     * corpus) before fusion. Wider than the final rerank topN so the cross-encoder
     * has a genuinely diverse pool to choose from. Raised from 20 to 30: as the
     * shared corpus grows across sessions/users, a strict top-20-per-retriever cut
     * risks losing a genuinely relevant chunk before the reranker even sees it —
     * rerank cost scales with pool size but is cheap (single-digit ms per doc on
     * CPU), so the extra headroom is close to free.
     */
    private static final int CANDIDATE_POOL_SIZE = 15;

    private final VectorStoreService vectorStoreService;
    private final DocumentChunkRepository documentChunkRepository;
    private final com.accenture.intern.docmind.config.RetrievalProperties retrievalProperties;

    public HybridRetrievalService(VectorStoreService vectorStoreService,
            DocumentChunkRepository documentChunkRepository,
            com.accenture.intern.docmind.config.RetrievalProperties retrievalProperties) {
        this.vectorStoreService = vectorStoreService;
        this.documentChunkRepository = documentChunkRepository;
        this.retrievalProperties = retrievalProperties;
    }

    /**
     * Runs dense + keyword retrieval across the entire company corpus in parallel,
     * fuses them with RRF, boosts chunks from the current session, and returns a
     * deduplicated, fused-rank-ordered candidate list ready for cross-encoder
     * reranking.
     *
     * @param sessionId the current session, used only for the relevance boost —
     *                  never as a search filter. May be null (e.g. no active
     *                  session yet), in which case no boost is applied.
     */
    /**
     * Words shorter than this are too generic/common to count as a meaningful
     * match between a query and a source filename ("the", "of", "pdf", "doc",
     * "cv", "resume" as a bare word, etc.) - only longer, more distinctive words
     * (typically names) are trusted to trigger whole-document mode.
     */
    private static final int MIN_SOURCE_MATCH_WORD_LENGTH = 4;

    public Mono<List<RetrievalCandidate>> retrieve(String query, Long sessionId) {
        return retrieve(List.of(), query, sessionId, CANDIDATE_POOL_SIZE, false, List.of(), false, null);
    }

    /**
     * Bypasses semantic/lexical search and directly fetches the SECTION_MAP structural 
     * overview chunk from Postgres. If targetDocuments is provided, restricts to those; 
     * otherwise searches by sessionId.
     */
    public Mono<List<RetrievalCandidate>> retrieveDocumentStructure(List<String> targetDocuments, Long sessionId) {
        return Mono.fromCallable(() -> {
            List<DocumentChunk> chunks;
            if (targetDocuments != null && !targetDocuments.isEmpty()) {
                chunks = documentChunkRepository.findSectionMapsBySourceNames(targetDocuments);
            } else if (sessionId != null) {
                chunks = documentChunkRepository.findSectionMapsBySession(sessionId);
            } else {
                return List.<RetrievalCandidate>of();
            }

            return chunks.stream()
                    .map(this::toDocument)
                    .map(doc -> {
                        doc.getMetadata().put("structuralMatch", true);
                        return new RetrievalCandidate(doc, 1.0); // Perfect score for explicit structural requests
                    })
                    .collect(Collectors.toList());
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Overload used by the MULTI_SOURCE wide-pool path and META_DOC_SEARCH.
     */
    public Mono<List<RetrievalCandidate>> retrieve(String query, Long sessionId, int candidatePoolSize) {
        return retrieve(List.of(), query, sessionId, candidatePoolSize, false, List.of(), false, null);
    }

    /**
     * Overload with skipWholeDocument flag — delegates with query as both filename and retrieval query.
     */
    public Mono<List<RetrievalCandidate>> retrieve(String query, Long sessionId, int candidatePoolSize,
            boolean skipWholeDocument) {
        return retrieve(List.of(), query, sessionId, candidatePoolSize, skipWholeDocument, List.of(), false, null);
    }

    /**
     * Canonical full overload.
     *
     * @param entities           the named entities extracted from the query (e.g. "Classroom of the Elite").
     *                           Used ONLY for whole-document filename detection via findUniquelyMatchedSource().
     *                           This replaces the raw user query, allowing dynamic NER-based matching
     *                           instead of hardcoded stop-words.
     * @param retrievalQuery     the optimized/expanded query — used for BM25 and
     *                           Pinecone dense search. May be LLM-rewritten.
     * @param sessionId          current session (for session boost, never a filter)
     * @param candidatePoolSize  topK per retriever before RRF fusion
     * @param skipWholeDocument  when true, bypasses findUniquelyMatchedSource entirely;
     *                           set by the RetrievalOrchestrator based on query provenance
     * @param imageOnly          when true, restricts both dense and keyword retrieval to chunks of type IMAGE.
     */
    public Mono<List<RetrievalCandidate>> retrieve(List<EntityResolution> entities, String retrievalQuery,
            Long sessionId, int candidatePoolSize, boolean skipWholeDocument, List<String> targetDocuments, boolean imageOnly, String imageType) {
        long t0 = System.currentTimeMillis();

        if (skipWholeDocument) {
            return rankedRetrieve(retrievalQuery, sessionId, t0, candidatePoolSize, targetDocuments, imageOnly, entities, imageType);
        }

        Mono<String> matchedSourceMono;
        if (targetDocuments != null && targetDocuments.size() == 1) {
            matchedSourceMono = Mono.just(targetDocuments.get(0));
        } else {
            matchedSourceMono = Mono.empty();
        }

        return matchedSourceMono
                .flatMap(matchedSource -> {
                    log.info(
                            "Query matched single source '{}' - using whole-document retrieval (capped), skipping corpus-wide ranking, imageOnly: {}",
                            matchedSource, imageOnly);
                    return Mono.fromCallable(
                            () -> {
                                List<DocumentChunk> chunks = documentChunkRepository.findBySourceNameIgnoreCaseOrderByChunkIndexAsc(matchedSource);
                                if (imageOnly) {
                                    chunks = chunks.stream()
                                            .filter(c -> (c.getImageUrl() != null && !c.getImageUrl().isEmpty()) || "IMAGE".equalsIgnoreCase(c.getSourceType()) || "PDF_IMAGE".equalsIgnoreCase(c.getSourceType()))
                                            .collect(Collectors.toList());
                                }
                                if (chunks.size() > 60) {
                                    log.warn("Document '{}' exceeds 60 chunks (total: {}). Truncating to prevent context flooding.", matchedSource, chunks.size());
                                    chunks = chunks.subList(0, 60);
                                }
                                return chunks.stream()
                                    .map(this::toDocument)
                                    .map(doc -> {
                                        doc.getMetadata().put("wholeDocumentMatch", true);
                                        return new RetrievalCandidate(doc, 1.0); // Highest possible base score
                                    })
                                    .collect(Collectors.toList());
                            })
                            .subscribeOn(Schedulers.boundedElastic());
                })
                .switchIfEmpty(Mono.defer(() -> rankedRetrieve(retrievalQuery, sessionId, t0, candidatePoolSize, targetDocuments, imageOnly, entities, imageType)));
    }



    /**
     * Returns every chunk of one source, in original document order, with no
     * similarity ranking applied. This is the whole point of whole-document
     * mode: a query that names a specific document deserves full coverage of
     * that document (all sections - skills, projects, achievements, etc.) rather
     * than a top-K slice chosen by relevance to the literal query wording, which
     * is what was causing "tell about X" to surface only 1-2 sections of X's
     * resume and miss the rest.
     */
    public Mono<List<RetrievalCandidate>> wholeDocumentRetrieve(String sourceName) {
        String normalizedSourceName = com.accenture.intern.docmind.util.FilenameNormalizer.normalize(sourceName);
        return Mono.fromCallable(() -> {
            List<DocumentChunk> chunks = documentChunkRepository.findBySourceNameIgnoreCaseOrderByChunkIndexAsc(sourceName);
            if (chunks.isEmpty() && !sourceName.equalsIgnoreCase(normalizedSourceName)) {
                chunks = documentChunkRepository.findBySourceNameIgnoreCaseOrderByChunkIndexAsc(normalizedSourceName);
            }
            return chunks;
        })
                .subscribeOn(Schedulers.boundedElastic())
                .map(chunks -> {
                    List<RetrievalCandidate> docs = chunks.stream()
                            .map(this::toDocument)
                            .map(doc -> {
                                doc.getMetadata().put("retrievalModeUsed", "WHOLE_DOCUMENT");
                                return new RetrievalCandidate(doc, 1.0);
                            })
                            .collect(Collectors.toList());
                    
                    if (docs.size() > 500) {
                        log.warn("WHOLE_DOCUMENT retrieval fetched {} chunks. Truncating to 500 to avoid context blowout before Map-Reduce is implemented.", docs.size());
                        docs = docs.subList(0, 500);
                    }
                    
                    log.info("Whole-document retrieval: {} chunks from '{}'", docs.size(), sourceName);
                    return docs;
                });
    }

    /**
     * CONTIGUOUS expansion: groups fully reranked anchor candidates by sectionPath,
     * selects the highest-scoring section, then returns all chunks in that section
     * in original reading order.
     */
    public Mono<List<RetrievalCandidate>> expandContiguous(
            List<RetrievalCandidate> anchors,
            String query,
            Long sessionId,
            List<String> targetDocuments) {

        return Mono.just(anchors)
                .flatMap(rankedAnchors -> {
                    // Filter: only chunks with a non-null sectionPath participate in grouping
                    List<RetrievalCandidate> validAnchors = anchors.stream()
                            .filter(c -> c.chunk().getMetadata().get("sectionPath") != null
                                    && !((String) c.chunk().getMetadata().get("sectionPath")).isBlank())
                            .collect(Collectors.toList());

                    if (validAnchors.isEmpty()) {
                        log.warn("CONTIGUOUS: no anchor chunks with sectionPath found for query='{}'. Falling back to ranked.", query);
                        return Mono.just(rankedAnchors);
                    }

                    // Group by sourceName|sectionPath, accumulate sum/max/count
                    Map<String, double[]> sectionScores = new LinkedHashMap<>(); // [sum, max, count]
                    Map<String, String[]> sectionMeta = new LinkedHashMap<>();  // [sourceName, sectionPath]
                    for (RetrievalCandidate c : validAnchors) {
                        String sourceName = (String) c.chunk().getMetadata().getOrDefault("sourceName", "");
                        String sectionPath = (String) c.chunk().getMetadata().get("sectionPath");
                        String key = sourceName + "|" + sectionPath;
                        sectionScores.computeIfAbsent(key, k -> new double[]{0.0, 0.0, 0.0});
                        double[] stats = sectionScores.get(key);
                        stats[0] += c.finalScore();                          // sum
                        stats[1] = Math.max(stats[1], c.finalScore());       // max
                        stats[2]++;                                           // count
                        sectionMeta.put(key, new String[]{sourceName, sectionPath});
                    }

                    // Pick winner by aggregate sum score
                    String winnerKey = sectionScores.entrySet().stream()
                            .max(Comparator.comparingDouble(e -> e.getValue()[0]))
                            .map(Map.Entry::getKey)
                            .orElse(null);

                    if (winnerKey == null) {
                        log.warn("CONTIGUOUS: section grouping produced no winner for query='{}'. Falling back to ranked.", query);
                        return Mono.just(rankedAnchors);
                    }

                    double[] winnerStats = sectionScores.get(winnerKey);
                    double winnerSum   = winnerStats[0];
                    double winnerMax   = winnerStats[1];
                    double winnerCount = winnerStats[2];
                    double winnerAvg   = winnerCount > 0 ? winnerSum / winnerCount : 0.0;
                    String[] meta = sectionMeta.get(winnerKey);
                    String sourceName  = meta[0];
                    String sectionPath = meta[1];

                    String sumStr = String.format("%.3f", winnerSum);
                    String avgStr = String.format("%.3f", winnerAvg);
                    String maxStr = String.format("%.3f", winnerMax);

                    log.info("CONTIGUOUS: anchor_query='{}' | anchor_chunks={} | winner_section='{}' | score[sum={} avg={} max={} count={}]",
                            query, validAnchors.size(), sectionPath,
                            sumStr, avgStr, maxStr, (int) winnerCount);

                    // Confidence check: if the winning section's aggregate score is below the
                    // configured threshold, don't expand — return ranked results instead
                    if (winnerSum < retrievalProperties.getContiguousMinConfidence()) {
                        log.warn("CONTIGUOUS: low expansion confidence (sum={} < threshold={}) for section='{}'. Falling back to ranked retrieval.",
                                sumStr, String.format("%.3f", retrievalProperties.getContiguousMinConfidence()), sectionPath);
                        return Mono.just(rankedAnchors);
                    }

                    // Expand: fetch all chunks in the winning section, in order
                    return Mono.fromCallable(
                            () -> documentChunkRepository.findBySourceNameAndSectionPathOrderByChunkIndexAsc(sourceName, sectionPath)
                    ).subscribeOn(Schedulers.boundedElastic()).map(chunks -> {
                        int totalChars = chunks.stream().mapToInt(c -> c.getContent() != null ? c.getContent().length() : 0).sum();
                        boolean withinBudget = totalChars <= retrievalProperties.getContiguousMaxChars();

                        // Always log — build the histogram from day one
                        log.info("CONTIGUOUS: expansion_section='{}' | expansion_chunks={} | expansion_chars={} | budget={} | within_budget={}",
                                sectionPath, chunks.size(), totalChars,
                                retrievalProperties.getContiguousMaxChars(), withinBudget);

                        if (!withinBudget) {
                            log.warn("CONTIGUOUS: section '{}' exceeds character budget ({} > {}). " +
                                    "Returning full section — LLM will handle context. " +
                                    "Consider level-walking or summarization if this is frequent.",
                                    sectionPath, totalChars, retrievalProperties.getContiguousMaxChars());
                        }

                        return chunks.stream()
                                .map(this::toDocument)
                                .map(doc -> {
                                    doc.getMetadata().put("retrievalModeUsed", "CONTIGUOUS");
                                    return new RetrievalCandidate(doc, 1.0);
                                })
                                .collect(Collectors.toList());
                    });
                });
    }


    private Mono<List<RetrievalCandidate>> rankedRetrieve(String query, Long sessionId, long t0, int candidatePoolSize, List<String> targetDocuments, boolean imageOnly, List<EntityResolution> entities, String imageType) {
        Mono<List<Document>> denseMono = vectorStoreService.retrieve(query, imageOnly ? 30 : candidatePoolSize, targetDocuments, imageOnly, entities, imageType)
                .doOnNext(docs -> log.info("[TIMING] dense (Pinecone) search: {}ms, {} hits",
                        System.currentTimeMillis() - t0, docs.size()));
        Mono<List<Document>> keywordMono = keywordSearch(query, imageOnly ? 30 : candidatePoolSize, imageOnly, entities, imageType)
                .doOnNext(docs -> log.info("[TIMING] keyword (Postgres BM25) search: {}ms, {} hits",
                        System.currentTimeMillis() - t0, docs.size()));

        return Mono.zip(denseMono, keywordMono)
                .map(tuple -> fuse(tuple.getT1(), tuple.getT2()))
                .flatMap(fused -> {
                    if (fused.isEmpty() && imageOnly && entities != null && !entities.isEmpty()) {
                        log.warn("Strict metadata filter yielded 0 results. Falling back to pure semantic visual search...");
                        Mono<List<Document>> fallbackDense = vectorStoreService.retrieve(query, 30, targetDocuments, imageOnly, null, imageType);
                        Mono<List<Document>> fallbackKeyword = keywordSearch(query, 30, imageOnly, null, imageType);
                        return Mono.zip(fallbackDense, fallbackKeyword)
                                .map(tuple2 -> fuse(tuple2.getT1(), tuple2.getT2()));
                    }
                    return Mono.just(fused);
                })
                .doOnNext(fused -> {
                    Set<Object> sources = new LinkedHashSet<>();
                    for (RetrievalCandidate cand : fused) {
                        sources.add(cand.chunk().getMetadata().getOrDefault("sourceName", "unknown"));
                    }
                    log.info(
                            "Hybrid retrieval: fused {} unique candidates company-wide (boosting session {}); sources in pool: {}",
                            fused.size(), sessionId, sources);
                });
    }

    private Mono<List<Document>> keywordSearch(String query, int topK, boolean imageOnly, List<EntityResolution> entities, String imageType) {
        String[] tokens = query.toLowerCase().split("\\s+");
        if (tokens.length < 3) {
            log.info("[ROUTING] BM25 skipped — query too short");
            return Mono.just(Collections.emptyList());
        }

        return Mono.fromCallable(() -> {
                    if (imageOnly) {
                        if (entities != null && !entities.isEmpty()) {
                            List<String> tags = entities.stream().map(EntityResolution::canonicalEntity).toList();
                            return documentChunkRepository.keywordSearchImagesWithTags(query, tags, topK, null);
                        } else {
                            return documentChunkRepository.keywordSearchImages(query, topK, null);
                        }
                    } else {
                        return documentChunkRepository.keywordSearch(query, topK);
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .map(chunks -> chunks.stream().map(this::toDocument).collect(Collectors.toList()))
                .onErrorResume(e -> {
                    // A malformed/empty tsquery (e.g. a question that's pure punctuation or
                    // stopwords) should never break retrieval — just fall back to dense-only.
                    log.warn("Keyword (BM25) search failed, continuing with dense results only: {}", e.getMessage());
                    return Mono.just(List.of());
                });
    }

    private Document toDocument(DocumentChunk chunk) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("sourceName", chunk.getSourceName());
        metadata.put("sourceType", chunk.getSourceType());
        metadata.put("chunkIndex", chunk.getChunkIndex() == null ? 0 : chunk.getChunkIndex());
        metadata.put("sessionId", chunk.getSessionId());
        metadata.put("retrievalPath", "bm25");
        if (chunk.getImageUrl() != null && !chunk.getImageUrl().isBlank()) {
            metadata.put("imageUrl", chunk.getImageUrl());
            metadata.put("isImage", true);
        }
        // Without this, any chunk surfaced via the BM25/Postgres path (or
        // whole-document retrieval, which also goes through this method) loses
        // its Cloudinary source link by the time it reaches CitationService -
        // the dense/Pinecone path keeps sourceUrl because it returns Documents
        // straight from the vector store with their original embedding-time
        // metadata intact, but this path rebuilds the Document from scratch.
        if (chunk.getSourceUrl() != null && !chunk.getSourceUrl().isBlank()) {
            metadata.put("sourceUrl", chunk.getSourceUrl());
        }
        if (chunk.getPage() != null) metadata.put("page", chunk.getPage());
        if (chunk.getCharStart() != null) metadata.put("charStart", chunk.getCharStart());
        if (chunk.getCharEnd() != null) metadata.put("charEnd", chunk.getCharEnd());
        if (chunk.getSectionPath() != null && !chunk.getSectionPath().isBlank()) metadata.put("sectionPath", chunk.getSectionPath());
        if (chunk.getHeading() != null && !chunk.getHeading().isBlank()) metadata.put("heading", chunk.getHeading());
        if (chunk.getBoundingBoxes() != null && !chunk.getBoundingBoxes().isBlank()) metadata.put("boundingBoxes", chunk.getBoundingBoxes());
        
        return new Document(chunk.getVectorId(), chunk.getContent(), metadata);
    }

    /**
     * Reciprocal Rank Fusion: base score(doc) = sum over
     * each list it appears in of 1 / (k + rank).
     * Documents found by both retrievers naturally rise to the top since they
     * accumulate a score contribution from each list.
     */
    private List<RetrievalCandidate> fuse(List<Document> denseResults, List<Document> keywordResults) {
        Map<String, Double> rrfScores = new HashMap<>();
        Map<String, Document> byId = new LinkedHashMap<>();

        accumulateRrf(denseResults, rrfScores, byId, "dense");
        accumulateRrf(keywordResults, rrfScores, byId, "bm25");

        List<Document> sortedDocuments = byId.values().stream()
                .sorted((a, b) -> Double.compare(
                        rrfScores.getOrDefault(b.getId(), 0.0),
                        rrfScores.getOrDefault(a.getId(), 0.0)))
                .collect(Collectors.toList());

        List<Document> diverseDocs = ensureSourceDiversity(sortedDocuments, 15);
        
        return diverseDocs.stream()
                .map(doc -> new RetrievalCandidate(doc, rrfScores.getOrDefault(doc.getId(), 0.0)))
                .collect(Collectors.toList());
    }

    private List<Document> ensureSourceDiversity(List<Document> candidates, int maxSize) {
        if (candidates.size() <= maxSize) {
            return candidates;
        }

        List<Document> diversePool = new ArrayList<>();
        Map<String, Integer> sourceCounts = new HashMap<>();
        Set<String> addedIds = new HashSet<>();

        // 1. Guarantee up to 2 chunks per unique sourceName
        for (Document doc : candidates) {
            String sourceName = (String) doc.getMetadata().getOrDefault("sourceName", "unknown");
            int count = sourceCounts.getOrDefault(sourceName, 0);
            if (count < 2) {
                diversePool.add(doc);
                addedIds.add(doc.getId());
                sourceCounts.put(sourceName, count + 1);
            }
        }

        // 2. Fill the rest up to maxSize with the highest ranked remaining chunks
        for (Document doc : candidates) {
            if (diversePool.size() >= maxSize) {
                break;
            }
            if (!addedIds.contains(doc.getId())) {
                diversePool.add(doc);
                addedIds.add(doc.getId());
            }
        }

        // 3. Preserve the original RRF-sorted order
        List<Document> finalPool = new ArrayList<>();
        for (Document doc : candidates) {
            if (addedIds.contains(doc.getId())) {
                finalPool.add(doc);
            }
        }

        return finalPool;
    }

    private void accumulateRrf(List<Document> results, Map<String, Double> rrfScores,
            Map<String, Document> byId, String pathLabel) {
        for (int rank = 0; rank < results.size(); rank++) {
            Document doc = results.get(rank);
            String id = doc.getId();
            double contribution = 1.0 / (RRF_K + rank + 1);
            rrfScores.merge(id, contribution, Double::sum);

            // Keep one copy per id, but tag it with every retrieval path that surfaced
            // it (useful for debugging / showing "found via keyword + semantic" in the
            // UI later). Document.getMetadata() may be unmodifiable, so we copy into a
            // fresh map and build a new Document rather than mutating in place.
            Document existing = byId.get(id);
            if (existing == null) {
                Map<String, Object> metadata = new HashMap<>(doc.getMetadata());
                metadata.put("retrievalPaths", new LinkedHashSet<>(Set.of(pathLabel)));
                byId.put(id, new Document(doc.getId(), doc.getText(), metadata));
            } else {
                Map<String, Object> metadata = new HashMap<>(existing.getMetadata());
                Object pathsObj = metadata.get("retrievalPaths");
                Set<String> paths = pathsObj instanceof Set<?> s
                        ? new LinkedHashSet<>((Set<String>) s)
                        : new LinkedHashSet<>();
                paths.add(pathLabel);
                metadata.put("retrievalPaths", paths);
                byId.put(id, new Document(existing.getId(), existing.getText(), metadata));
            }
        }
    }

    private Long toLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}

