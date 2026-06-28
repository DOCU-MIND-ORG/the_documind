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

    public HybridRetrievalService(VectorStoreService vectorStoreService,
            DocumentChunkRepository documentChunkRepository) {
        this.vectorStoreService = vectorStoreService;
        this.documentChunkRepository = documentChunkRepository;
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
        return retrieve(List.of(), query, sessionId, CANDIDATE_POOL_SIZE, false, List.of());
    }

    /**
     * Overload used by the MULTI_SOURCE wide-pool path and META_DOC_SEARCH.
     */
    public Mono<List<RetrievalCandidate>> retrieve(String query, Long sessionId, int candidatePoolSize) {
        return retrieve(List.of(), query, sessionId, candidatePoolSize, false, List.of());
    }

    /**
     * Overload with skipWholeDocument flag — delegates with query as both filename and retrieval query.
     */
    public Mono<List<RetrievalCandidate>> retrieve(String query, Long sessionId, int candidatePoolSize,
            boolean skipWholeDocument) {
        return retrieve(List.of(), query, sessionId, candidatePoolSize, skipWholeDocument, List.of());
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
     */
    public Mono<List<RetrievalCandidate>> retrieve(List<EntityResolution> entities, String retrievalQuery,
            Long sessionId, int candidatePoolSize, boolean skipWholeDocument, List<String> targetDocuments) {
        long t0 = System.currentTimeMillis();

        if (skipWholeDocument) {
            return rankedRetrieve(retrievalQuery, sessionId, t0, candidatePoolSize, targetDocuments);
        }

        // Filename detection uses the LLM-extracted entities; retrieval uses the expanded form
        return findUniquelyMatchedSource(entities, sessionId)
                .flatMap(matchedSource -> {
                    log.info(
                            "Query matched single source '{}' - using whole-document retrieval (capped), skipping corpus-wide ranking",
                            matchedSource);
                    return Mono.fromCallable(
                            () -> {
                                List<DocumentChunk> chunks = documentChunkRepository.findBySourceNameIgnoreCaseOrderByChunkIndexAsc(matchedSource);
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
                .switchIfEmpty(Mono.defer(() -> rankedRetrieve(retrievalQuery, sessionId, t0, candidatePoolSize, targetDocuments)));
    }

    /**
     * Checks whether the LLM-extracted entities contain a distinctive word (length >=
     * MIN_SOURCE_MATCH_WORD_LENGTH) that also appears in exactly one uploaded
     * source's filename. Word-boundary matching only (not substring), so e.g.
     * "hitesh" matches "Hitesh_Resume.pdf" but doesn't accidentally match
     * unrelated filenames that merely contain "hitesh" as part of a longer word.
     * <p>
     * Returns empty (not an error) when zero or 2+ sources match - ambiguous or
     * no match both mean "fall back to normal ranked retrieval", since whole-
     * document mode only makes sense when exactly one source is clearly meant.
     */
    private Mono<String> findUniquelyMatchedSource(List<EntityResolution> entities, Long sessionId) {
        if (entities == null || entities.isEmpty()) {
            return Mono.empty();
        }

        // Combine all words from all extracted entities
        Set<String> entityWords = entities.stream()
                .filter(e -> e != null && e.canonicalEntity() != null && !e.canonicalEntity().isBlank())
                .flatMap(e -> wordsOf(e.canonicalEntity()).stream())
                .filter(w -> w.length() >= MIN_SOURCE_MATCH_WORD_LENGTH)
                .collect(Collectors.toSet());

        if (entityWords.isEmpty()) {
            return Mono.empty();
        }

        return Mono.fromCallable(() -> documentChunkRepository.findDistinctSourceNames())
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(sourceNames -> {
                    String bestMatch = null;
                    int maxScore = 0;
                    boolean isTie = false;

                    for (String sourceName : sourceNames) {
                        if (sourceName == null)
                            continue;
                        Set<String> sourceWords = wordsOf(sourceName);
                        
                        int overlapCount = 0;
                        for (String ew : entityWords) {
                            if (sourceWords.contains(ew)) {
                                overlapCount++;
                            }
                        }

                        if (overlapCount > 0) {
                            if (overlapCount > maxScore) {
                                maxScore = overlapCount;
                                bestMatch = sourceName;
                                isTie = false;
                            } else if (overlapCount == maxScore) {
                                isTie = true;
                            }
                        }
                    }

                    if (bestMatch != null && !isTie) {
                        log.info("Scored filename match: '{}' won with overlap score {}", bestMatch, maxScore);
                        return Mono.just(bestMatch);
                    }

                    if (isTie) {
                        log.warn("Filename match tie detected at score {}. Falling back to ranked vector retrieval.", maxScore);
                    }

                    return Mono.empty();
                });
    }

    /**
     * Lowercased, punctuation-stripped words - used for both query and filename
     * matching.
     */
    private Set<String> wordsOf(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(text.toLowerCase().split("[^a-z0-9]+"))
                .filter(w -> !w.isBlank())
                .collect(Collectors.toSet());
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
        // Callers (e.g. ContextBuilderService's SPECIFIC_DOC path) may pass the
        // raw filename the LLM resolved (e.g. "Jio_5G_Rollout_Project.pdf"), but
        // DocumentChunk.sourceName is always persisted in normalized form
        // (extension stripped, underscores/hyphens -> spaces, lowercased - see
        // EmbeddingService / FilenameNormalizer). Without normalizing here first,
        // findBySourceNameIgnoreCaseOrderByChunkIndexAsc never matches anything
        // and whole-document retrieval silently returns 0 chunks, forcing a fall
        // back to noisy, company-wide adaptive search for what should have been
        // an instant, exact lookup.
        String normalizedSourceName = com.accenture.intern.docmind.util.FilenameNormalizer.normalize(sourceName);
        return Mono.fromCallable(() -> documentChunkRepository.findBySourceNameIgnoreCaseOrderByChunkIndexAsc(normalizedSourceName))
                .subscribeOn(Schedulers.boundedElastic())
                .map(chunks -> {
                    List<RetrievalCandidate> docs = chunks.stream()
                            .map(this::toDocument)
                            .map(doc -> {
                                doc.getMetadata().put("wholeDocumentMatch", true);
                                return new RetrievalCandidate(doc, 1.0);
                            })
                            .collect(Collectors.toList());
                    log.info("Whole-document retrieval: {} chunks from '{}'", docs.size(), sourceName);
                    return docs;
                });
    }

    private Mono<List<RetrievalCandidate>> rankedRetrieve(String query, Long sessionId, long t0, int candidatePoolSize, List<String> targetDocuments) {
        Mono<List<Document>> denseMono = vectorStoreService.retrieve(query, candidatePoolSize, targetDocuments)
                .doOnNext(docs -> log.info("[TIMING] dense (Pinecone) search: {}ms, {} hits",
                        System.currentTimeMillis() - t0, docs.size()));
        Mono<List<Document>> keywordMono = keywordSearch(query, candidatePoolSize)
                .doOnNext(docs -> log.info("[TIMING] keyword (Postgres BM25) search: {}ms, {} hits",
                        System.currentTimeMillis() - t0, docs.size()));

        return Mono.zip(denseMono, keywordMono)
                .map(tuple -> fuse(tuple.getT1(), tuple.getT2()))
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

    private Mono<List<Document>> keywordSearch(String query, int topK) {
        String[] tokens = query.toLowerCase().split("\\s+");
        if (tokens.length < 3) {
            log.info("[ROUTING] BM25 skipped — query too short");
            return Mono.just(Collections.emptyList());
        }

        return Mono.fromCallable(() -> documentChunkRepository.keywordSearch(query, topK))
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

