package com.accenture.intern.docmind.aiservices;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

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

    public Mono<List<Document>> retrieve(String query, int topK) {
        return Mono.fromCallable(() ->
                vectorStore.similaritySearch(
                        SearchRequest.builder()
                                .query(query)
                                .topK(topK)
                                .similarityThreshold(0.3)
                                .build()
                )
        ).subscribeOn(Schedulers.boundedElastic());
    }
}
