package com.accenture.intern.docmind.aiservices;

import com.accenture.intern.docmind.entity.DocumentChunk;
import com.accenture.intern.docmind.repository.DocumentChunkRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Hybrid retrieval for DocMind's shared company knowledge base.
 * <p>
 * DocMind is intentionally NOT session-isolated: any document ever uploaded by
 * anyone, in any session, is searchable from every other session. This service
 * runs BM25-style keyword search (Postgres full-text, via DocumentChunkRepository)
 * and dense semantic search (Pinecone, via VectorStoreService) across the *entire*
 * corpus in parallel, then fuses the two ranked lists with Reciprocal Rank Fusion
 * (RRF).
 * <p>
 * On top of plain RRF, chunks that belong to the *current* session get a relevance
 * boost — not a filter. The intuition: if you're actively chatting in a session
 * where you uploaded a doc, a matching chunk from that doc is more likely to be
 * what you mean, so it's worth nudging up the ranking. But it's still a real
 * company-wide search underneath — a strongly relevant chunk from someone else's
 * document in another session can still outrank a weak match from your own
 * session, which is the point of a *soft* boost rather than a hard session filter.
 * <p>
 * Why RRF instead of combining raw scores: BM25/ts_rank scores and Pinecone
 * similarity scores live on completely different, unbounded scales, so averaging
 * or weighting them directly is unreliable and needs constant re-tuning. RRF only
 * looks at each document's *rank* in each list, which makes it scale-free and the
 * de facto standard for combining lexical + dense retrieval (this is the same
 * technique Elasticsearch, Weaviate, and Azure AI Search use for their hybrid
 * search modes). The session boost is applied as a multiplier on top of each
 * document's RRF score, so it composes cleanly with fusion instead of needing its
 * own separate scale.
 * <p>
 * Why fuse before reranking rather than just picking the dense results: keyword
 * search catches exact terms, acronyms, IDs, names, and rare words that an
 * embedding model can blur together (e.g. a product code or a person's name), while
 * dense search catches paraphrases and conceptual matches that share no words with
 * the query. Fusing the two before the cross-encoder rerank means the reranker
 * always gets the best candidates from both worlds rather than only one.
 */
@Slf4j
@Service
public class HybridRetrievalService {

    /** RRF damping constant. 60 is the standard value used in the original RRF paper
     *  and in most production hybrid-search implementations; it controls how quickly
     *  a document's contribution falls off as its rank gets worse. */
    private static final int RRF_K = 60;

    /** How many candidates to pull from each retriever (across the whole company
     *  corpus) before fusion. Wider than the final rerank topN so the cross-encoder
     *  has a genuinely diverse pool to choose from. Raised from 20 to 30: as the
     *  shared corpus grows across sessions/users, a strict top-20-per-retriever cut
     *  risks losing a genuinely relevant chunk before the reranker even sees it —
     *  rerank cost scales with pool size but is cheap (single-digit ms per doc on
     *  CPU), so the extra headroom is close to free. */
    private static final int CANDIDATE_POOL_SIZE = 30;

    /**
     * Multiplier applied to a chunk's fused RRF score when it belongs to the
     * current session. 1.3 is a deliberately gentle boost: a chunk that's only
     * weakly relevant (low rank in both retrievers) still won't out-rank a chunk
     * that's strongly relevant company-wide, because 1.3x of a small number is
     * still small. It mainly breaks ties and nudges genuinely-relevant
     * same-session chunks slightly ahead of equally-relevant chunks from elsewhere.
     */
    private static final double SESSION_BOOST_MULTIPLIER = 1.3;

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

    public Mono<List<Document>> retrieve(String query, Long sessionId) {
        return retrieve(query, sessionId, Set.of(), null);
    }

    /**
     * Same as {@link #retrieve(String, Long)}, with two extra, independent
     * narrowing controls layered on top of the normal hybrid search:
     *
     * @param excludedSources exact sourceName values that must never appear in
     *                        the returned candidates, no matter how well they'd
     *                        otherwise rank (e.g. user said "... except the SQL
     *                        notes pdf"). Empty/null means no exclusion.
     * @param scopedSources   if non-null and non-empty, restricts retrieval to
     *                        ONLY these sourceName values (e.g. a follow-up like
     *                        "compare these two" that should stay anchored to
     *                        the documents the previous answer actually used,
     *                        rather than re-searching the whole company corpus).
     *                        Null/empty means no restriction - normal corpus-wide
     *                        search.
     */
    public Mono<List<Document>> retrieve(String query, Long sessionId, Set<String> excludedSources, Set<String> scopedSources) {
        long t0 = System.currentTimeMillis();
        Set<String> exclusions = excludedSources == null ? Set.of() : excludedSources;

        if (scopedSources != null && !scopedSources.isEmpty()) {
            log.info("Query is a follow-up referencing prior sources {} - scoping retrieval to just those, skipping whole-corpus ranking", scopedSources);
            return scopedSourcesRetrieve(scopedSources, exclusions);
        }

        return findUniquelyMatchedSource(query, exclusions)
                .flatMap(matchedSource -> {
                    log.info("Query matched single source '{}' - using whole-document retrieval, skipping corpus-wide ranking", matchedSource);
                    return wholeDocumentRetrieve(matchedSource);
                })
                .switchIfEmpty(Mono.defer(() -> rankedRetrieve(query, sessionId, t0, exclusions)));
    }

    /**
     * Public entry point for fetching every chunk of a known set of source
     * names directly, in document order, with no relevance ranking - the
     * same whole-document fetch {@link #retrieve} uses internally for
     * follow-up scoping, exposed here for callers (like
     * ContextBuilderService's deictic-upload branch) that already know
     * exactly which source(s) they want and don't need the full intent-
     * resolution pipeline in {@link #retrieve} to get there.
     */
    public Mono<List<Document>> fetchSourcesByName(Set<String> sourceNames) {
        return scopedSourcesRetrieve(sourceNames, Set.of());
    }

    /**
     * Retrieves every chunk of each named source (in document order, no
     * relevance ranking - same rationale as {@link #wholeDocumentRetrieve}),
     * used when a follow-up question has been anchored to a specific set of
     * previously-discussed documents rather than re-ranked against the whole
     * corpus. Any source in excludedSources is dropped even if it was in
     * scopedSources, so "compare these two, except the resume" still works.
     */
    private Mono<List<Document>> scopedSourcesRetrieve(Set<String> scopedSources, Set<String> excludedSources) {
        Set<String> effectiveSources = new LinkedHashSet<>(scopedSources);
        effectiveSources.removeAll(excludedSources);

        if (effectiveSources.isEmpty()) {
            return Mono.just(List.of());
        }

        return Mono.fromCallable(() -> {
                    List<Document> docs = new ArrayList<>();
                    for (String sourceName : effectiveSources) {
                        documentChunkRepository.findBySourceNameOrderByChunkIndexAsc(sourceName).stream()
                                .map(this::toDocument)
                                .peek(doc -> doc.getMetadata().put("wholeDocumentMatch", true))
                                .forEach(docs::add);
                    }
                    return docs;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(docs -> log.info("Scoped multi-source retrieval: {} chunks from {}", docs.size(), effectiveSources));
    }

    /**
     * Checks whether the query contains a word that DISTINCTIVELY identifies
     * exactly one uploaded source - i.e. a shared word, length >=
     * MIN_SOURCE_MATCH_WORD_LENGTH, that appears in that source's filename
     * AND in no other source's filename. Word-boundary matching only (not
     * substring), so e.g. "hitesh" matches "Hitesh_Resume.pdf" but doesn't
     * accidentally match unrelated filenames that merely contain "hitesh" as
     * part of a longer word.
     * <p>
     * Document-frequency filtering matters once the corpus has more than a
     * couple of files: a query like "the flipkart project" naively overlaps
     * BOTH "Flipkart_Supply_Chain.pdf" (on "flipkart") AND any other
     * "..._Project.pdf" / "project_..." filename (on "project") - without
     * filtering out words that appear in multiple filenames, that reads as
     * "2 sources matched, ambiguous" and falls through to ranked search even
     * though "flipkart" alone is obviously what the user meant. Requiring the
     * overlap to include at least one word unique to that single filename
     * fixes this: "project" is excluded as non-distinctive once 2+ filenames
     * contain it, leaving "flipkart" as the sole distinguishing word.
     * <p>
     * Returns empty (not an error) when zero or 2+ sources have a distinctive
     * match - ambiguous or no match both mean "fall back to normal ranked
     * retrieval", since whole-document mode only makes sense when exactly one
     * source is clearly meant.
     */
    private Mono<String> findUniquelyMatchedSource(String query, Set<String> excludedSources) {
        Set<String> queryWords = wordsOf(query).stream()
                .filter(w -> w.length() >= MIN_SOURCE_MATCH_WORD_LENGTH)
                .collect(Collectors.toSet());

        if (queryWords.isEmpty()) {
            return Mono.empty();
        }

        return Mono.fromCallable(documentChunkRepository::findDistinctSourceNames)
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(sourceNames -> {
                    Set<String> matched = findDistinctivelyMatchedSources(queryWords, sourceNames, excludedSources);
                    if (matched.size() == 1) {
                        return Mono.just(matched.iterator().next());
                    }
                    if (matched.size() > 1) {
                        log.info("Query matched {} sources ({}) - ambiguous, falling back to ranked retrieval", matched.size(), matched);
                    }
                    return Mono.empty();
                });
    }

    /**
     * Lowercased words for query/filename matching - splits on non-alphanumeric
     * runs AND on camelCase boundaries (lower/digit -> upper), so a filename
     * like "CoverLetter_Priya.pdf" tokenizes to {cover, letter, priya, pdf}
     * instead of one fused {coverletter, priya, pdf} that can never match the
     * separate words a user actually types ("the cover letter"). Must run
     * camelCase splitting BEFORE lowercasing - the case information that
     * marks the boundary is gone once everything is already lowercase.
     */
    /**
     * Shared by both whole-document single-source matching (above) and
     * QueryIntentService's comparison-target resolution, which needs the
     * exact same "distinctive word, not just any shared word" matching
     * against the same corpus filenames - kept in one place rather than
     * reimplemented twice with the risk of the two drifting out of sync.
     */
    public Set<String> findDistinctivelyMatchedSources(Set<String> queryWords, List<String> sourceNames, Set<String> excludedSources) {
        Map<String, Set<String>> sourceWordsByName = new LinkedHashMap<>();
        for (String sourceName : sourceNames) {
            if (sourceName == null || excludedSources.contains(sourceName)) continue;
            sourceWordsByName.put(sourceName, wordsOf(sourceName));
        }

        // Document frequency: how many DIFFERENT filenames in the corpus
        // contain each word. A word with df >= 2 (e.g. "project" shared by
        // several "..._project_..." files) carries no distinguishing power -
        // matching on it alone can't tell which of those files is meant, so
        // it's excluded from counting as a real match below.
        Map<String, Integer> docFreq = new HashMap<>();
        for (Set<String> words : sourceWordsByName.values()) {
            for (String w : words) {
                docFreq.merge(w, 1, Integer::sum);
            }
        }

        Set<String> matched = new LinkedHashSet<>();
        for (Map.Entry<String, Set<String>> entry : sourceWordsByName.entrySet()) {
            boolean hasDistinctiveOverlap = entry.getValue().stream()
                    .anyMatch(w -> queryWords.contains(w) && docFreq.get(w) == 1);
            if (hasDistinctiveOverlap) {
                matched.add(entry.getKey());
            }
        }
        return matched;
    }

    private Set<String> wordsOf(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        Set<String> words = new LinkedHashSet<>();
        for (String token : text.split("[^a-zA-Z0-9]+")) {
            if (token.isBlank()) continue;
            String spaced = token.replaceAll("(?<=[a-z0-9])(?=[A-Z])", " ");
            for (String w : spaced.toLowerCase().split("\\s+")) {
                if (!w.isBlank()) words.add(w);
            }
        }
        return words;
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
    private Mono<List<Document>> wholeDocumentRetrieve(String sourceName) {
        return Mono.fromCallable(() -> documentChunkRepository.findBySourceNameOrderByChunkIndexAsc(sourceName))
                .subscribeOn(Schedulers.boundedElastic())
                .map(chunks -> {
                    List<Document> docs = chunks.stream()
                            .map(this::toDocument)
                            .peek(doc -> doc.getMetadata().put("wholeDocumentMatch", true))
                            .collect(Collectors.toList());
                    log.info("Whole-document retrieval: {} chunks from '{}'", docs.size(), sourceName);
                    return docs;
                });
    }

    private Mono<List<Document>> rankedRetrieve(String query, Long sessionId, long t0, Set<String> excludedSources) {
        Mono<List<Document>> denseMono = vectorStoreService.retrieve(query, CANDIDATE_POOL_SIZE)
                .map(docs -> filterExcluded(docs, excludedSources))
                .doOnNext(docs -> log.info("[TIMING] dense (Pinecone) search: {}ms, {} hits",
                        System.currentTimeMillis() - t0, docs.size()));
        Mono<List<Document>> keywordMono = keywordSearch(query, CANDIDATE_POOL_SIZE)
                .map(docs -> filterExcluded(docs, excludedSources))
                .doOnNext(docs -> log.info("[TIMING] keyword (Postgres BM25) search: {}ms, {} hits",
                        System.currentTimeMillis() - t0, docs.size()));

        return Mono.zip(denseMono, keywordMono)
                .map(tuple -> fuse(tuple.getT1(), tuple.getT2(), sessionId))
                .doOnNext(fused -> {
                    Set<Object> sources = new LinkedHashSet<>();
                    for (Document doc : fused) {
                        sources.add(doc.getMetadata().getOrDefault("sourceName", "unknown"));
                    }
                    log.info(
                        "Hybrid retrieval: fused {} unique candidates company-wide (boosting session {}, excluding {}); sources in pool: {}",
                        fused.size(), sessionId, excludedSources, sources);
                });
    }

    /**
     * Drops any candidate whose sourceName is in excludedSources, before it ever
     * reaches RRF fusion or the reranker. Filtering this early (rather than after
     * fusion, or worse, after rerank) means an excluded source can't consume any
     * of the candidate pool's limited slots, and can't influence RRF rank
     * contributions for the documents that ARE kept.
     */
    private List<Document> filterExcluded(List<Document> docs, Set<String> excludedSources) {
        if (excludedSources == null || excludedSources.isEmpty()) {
            return docs;
        }
        return docs.stream()
                .filter(doc -> !excludedSources.contains(doc.getMetadata().get("sourceName")))
                .collect(Collectors.toList());
    }

    private Mono<List<Document>> keywordSearch(String query, int topK) {
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
        if (chunk.getSourceUrl() != null && !chunk.getSourceUrl().isBlank()) {
            metadata.put("sourceUrl", chunk.getSourceUrl());
        }
        return new Document(chunk.getVectorId(), chunk.getContent(), metadata);
    }

    /**
     * Reciprocal Rank Fusion with a same-session boost: base score(doc) = sum over
     * each list it appears in of 1 / (k + rank), then multiplied by
     * SESSION_BOOST_MULTIPLIER if the chunk's sessionId matches the current session.
     * Documents found by both retrievers naturally rise to the top since they
     * accumulate a score contribution from each list; same-session documents get an
     * additional nudge on top of that.
     */
    private List<Document> fuse(List<Document> denseResults, List<Document> keywordResults, Long currentSessionId) {
        Map<String, Double> rrfScores = new HashMap<>();
        Map<String, Document> byId = new LinkedHashMap<>();

        accumulateRrf(denseResults, rrfScores, byId, "dense");
        accumulateRrf(keywordResults, rrfScores, byId, "bm25");

        if (currentSessionId != null) {
            for (Map.Entry<String, Document> entry : byId.entrySet()) {
                Object docSessionId = entry.getValue().getMetadata().get("sessionId");
                if (currentSessionId.equals(toLong(docSessionId))) {
                    rrfScores.computeIfPresent(entry.getKey(), (id, score) -> score * SESSION_BOOST_MULTIPLIER);
                }
            }
        }

        return byId.values().stream()
                .sorted((a, b) -> Double.compare(
                        rrfScores.getOrDefault(b.getId(), 0.0),
                        rrfScores.getOrDefault(a.getId(), 0.0)))
                .collect(Collectors.toList());
    }

    /** Metadata values can come back as Long, Integer, or even String depending on
     *  whether they round-tripped through Pinecone's JSON or stayed in the JVM, so
     *  compare numerically rather than assuming a single boxed type. */
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
}
