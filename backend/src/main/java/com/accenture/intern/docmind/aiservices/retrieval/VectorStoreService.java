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
import java.time.Duration;
import java.util.stream.Collectors;

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

    private static final int BATCH_SIZE = 96;
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
                .delayElements(Duration.ofSeconds(4))
                .concatMap(batch -> Mono.fromCallable(() -> {
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
    public Mono<List<Document>> retrieve(String query, int topK, List<String> targetDocuments, boolean imageOnly, List<com.accenture.intern.docmind.aiservices.understanding.EntityResolution> entities, String imageType) {
        return Mono.fromCallable(() -> {
                    SearchRequest.Builder builder = SearchRequest.builder()
                            .query(query)
                            .topK(topK)
                            .similarityThreshold(0.0);

                    String filterStr = "";
                    
                    if (targetDocuments != null && !targetDocuments.isEmpty()) {
                        String docs = targetDocuments.stream()
                                .map(com.accenture.intern.docmind.util.FilenameNormalizer::normalize)
                                .map(d -> "'" + d.replace("'", "\\'") + "'")
                                .collect(Collectors.joining(", "));
                        filterStr += "sourceName in [" + docs + "]";
                    }

                    if (imageOnly) {
                        String imgStr = "isImage == true";

                        if (entities != null && !entities.isEmpty()) {
                            List<String> entityOrs = new java.util.ArrayList<>();
                            for (com.accenture.intern.docmind.aiservices.understanding.EntityResolution entity : entities) {
                                String name = entity.canonicalEntity().replace("'", "\\'");
                                entityOrs.add(String.format("(entities in ['%s'] || topics in ['%s'] || objects in ['%s'] || technologies in ['%s'] || relationships in ['%s'])", 
                                    name, name, name, name, name));
                            }
                            imgStr += " && (" + String.join(" || ", entityOrs) + ")";
                        }
                        
                        if (filterStr.isEmpty()) {
                            filterStr = imgStr;
                        } else {
                            filterStr = "(" + filterStr + ") && (" + imgStr + ")";
                        }
                    }

                    if (!filterStr.isEmpty()) {
                        builder.filterExpression(filterStr);
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

