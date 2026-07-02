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
    public Mono<List<Document>> retrieve(String query, int topK, List<String> targetDocuments, boolean imageOnly) {
        return Mono.fromCallable(() -> {
                    SearchRequest.Builder builder = SearchRequest.builder()
                            .query(query)
                            .topK(topK)
                            .similarityThreshold(0.0);

                    org.springframework.ai.vectorstore.filter.Filter.Expression filter = null;
                    
                    if (targetDocuments != null && !targetDocuments.isEmpty()) {
                        List<String> normalizedTargets = targetDocuments.stream()
                                .map(com.accenture.intern.docmind.util.FilenameNormalizer::normalize)
                                .collect(java.util.stream.Collectors.toList());
                        filter = new org.springframework.ai.vectorstore.filter.FilterExpressionBuilder().in("sourceName", normalizedTargets.toArray()).build();
                    }

                    if (imageOnly) {
                        org.springframework.ai.vectorstore.filter.Filter.Expression imgFilter = new org.springframework.ai.vectorstore.filter.FilterExpressionBuilder().in("type", "IMAGE", "PDF_IMAGE").build();
                        if (filter != null) {
                            filter = new org.springframework.ai.vectorstore.filter.Filter.Expression(org.springframework.ai.vectorstore.filter.Filter.ExpressionType.AND, filter, imgFilter);
                        } else {
                            filter = imgFilter;
                        }
                    }

                    if (filter != null) {
                        builder.filterExpression(filter);
                    }

                    return vectorStore.similaritySearch(builder.build());
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    log.error("Dense retrieval failed, returning empty list", e);
                    return Mono.just(List.of());
                });
    }

    /**
     * Deletes vectors from Pinecone by id. IDs here should be
     * DocumentChunk.vectorId values — same id used on both sides (see
     * DocumentChunk class doc) — so a Postgres chunk lookup lines up 1:1 with
     * what needs removing from the vector index. Used by
     * AttachmentService#deleteExploreAttachment when a file being deleted from
     * Explore is owned by exactly one user.
     */
    public Mono<Void> deleteByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Mono.empty();
        }
        return Mono.fromRunnable(() -> vectorStore.delete(ids))
                .subscribeOn(Schedulers.boundedElastic())
                .then()
                .onErrorResume(e -> {
                    log.error("Failed to delete {} vectors from Pinecone", ids.size(), e);
                    return Mono.empty();
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

