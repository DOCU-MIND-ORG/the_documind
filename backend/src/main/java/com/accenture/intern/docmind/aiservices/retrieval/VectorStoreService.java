package com.accenture.intern.docmind.aiservices.retrieval;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/**
 * DocMind is a shared company knowledge base — any uploaded document is searchable
 * by anyone, from any session. Retrieval here is intentionally global, not scoped
 * to a single session. (Relevance for the *current* session's own uploads is
 * boosted later, during fusion in HybridRetrievalService — see RRF_SESSION_BOOST
 * there — rather than enforced as a hard filter here.)
 */
@Slf4j
@Service
public class VectorStoreService {

    private static final int BATCH_SIZE = 10;
    private final VectorStore vectorStore;

    public VectorStoreService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public Mono<Void> ingestDocuments(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return Mono.empty();
        }

        return Flux.fromIterable(documents)
                .buffer(BATCH_SIZE)
                .flatMap(batch -> Mono.fromCallable(() -> {
                    vectorStore.add(batch);
                    return batch.size();
                }).subscribeOn(Schedulers.boundedElastic()))
                .doOnNext(size -> log.debug("Flushed batch of {} documents", size))
                .then();
    }

    /**
     * Global dense (semantic) retrieval across every document ever uploaded to
     * DocMind, company-wide. similarityThreshold is left at 0.0 so the cross-encoder
     * reranker — not a raw cosine cutoff — decides what's actually relevant.
     */
    public Mono<List<Document>> retrieve(String query, int topK, List<String> targetDocuments) {
        return Mono.fromCallable(() -> {
                    SearchRequest.Builder builder = SearchRequest.builder()
                            .query(query)
                            .topK(topK)
                            .similarityThreshold(0.0);

                    if (targetDocuments != null && !targetDocuments.isEmpty()) {
                        List<String> normalizedTargets = targetDocuments.stream()
                                .map(com.accenture.intern.docmind.util.FilenameNormalizer::normalize)
                                .collect(java.util.stream.Collectors.toList());
                        builder.filterExpression(new org.springframework.ai.vectorstore.filter.FilterExpressionBuilder().in("sourceName", normalizedTargets.toArray()).build());
                    }

                    return vectorStore.similaritySearch(builder.build());
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    log.error("Dense retrieval failed, returning empty list", e);
                    return Mono.just(List.of());
                });
    }

    public Mono<List<Document>> retrieveSessionFallback(String query, int topK, Long sessionId) {
        return Mono.fromCallable(() -> {
                    SearchRequest.Builder builder = SearchRequest.builder()
                            .query(query)
                            .topK(topK)
                            .similarityThreshold(0.0)
                            .filterExpression("sessionId == " + sessionId);

                    return vectorStore.similaritySearch(builder.build());
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    log.error("Session fallback dense retrieval failed, returning empty list", e);
                    return Mono.just(List.of());
                });
    }
}

