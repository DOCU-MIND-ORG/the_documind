package com.accenture.intern.docmind.aiservices;

import lombok.extern.slf4j.Slf4j;
import com.accenture.intern.docmind.service.SessionCacheService;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import com.accenture.intern.docmind.dto.chat.EmbeddedDocument;
import com.accenture.intern.docmind.dto.chat.SessionUploadState;
import com.accenture.intern.docmind.dto.chat.UploadState;
import reactor.core.publisher.Mono;
import com.accenture.intern.docmind.aiservices.VectorStoreService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class EmbeddingService {

    private static final int CHUNK_SIZE = 1000;
    private static final int OVERLAP    = 200;

    private final VectorStoreService vectorStoreService;
    private final SessionCacheService sessionCacheService;

    public EmbeddingService(VectorStoreService vectorStoreService, SessionCacheService sessionCacheService) {
        this.vectorStoreService = vectorStoreService;
        this.sessionCacheService = sessionCacheService;
    }

    public Mono<Void> processAndIngest(String text, String sourceType, String sourceName, Long sessionId) {

    log.info("=== INGESTION STARTED ===");
    log.info("SessionId={}", sessionId);
    log.info("SourceName={}", sourceName);
    log.info("SourceType={}", sourceType);
    log.info("TextLength={}", text == null ? 0 : text.length());

    if (text == null || text.isBlank()) {
        log.warn("Skipping ingest — empty text for '{}'", sourceName);
        return Mono.empty();
    }

    SessionUploadState state = sessionCacheService.getOrCreateState(sessionId);
    state.setState(UploadState.EMBEDDING);

    log.info("Chunking document...");

    List<Document> documents = chunkText(text, sourceType, sourceName);

    log.info("Generated {} chunks", documents.size());

    if (!documents.isEmpty()) {
        log.info("First chunk preview: {}",
                documents.get(0).getText().substring(
                        0,
                        Math.min(200, documents.get(0).getText().length())
                ));
    }

    if (documents.isEmpty()) {
        log.warn("No chunks generated");
        state.setState(UploadState.READY);
        return Mono.empty();
    }

    List<EmbeddedDocument> embeddedDocs = documents.stream()
            .map(doc -> new EmbeddedDocument(doc, new float[0]))
            .toList();

    state.setEmbeddedDocuments(embeddedDocs);
    state.setState(UploadState.INGESTING);

    log.info("Calling VectorStoreService.ingestDocuments()");
    log.info("Chunks to ingest = {}", documents.size());

    return vectorStoreService.ingestDocuments(documents)
            .doOnSubscribe(sub ->
                    log.info("Pinecone ingestion subscribed"))
            .doOnSuccess(v -> {
                log.info("=== INGESTION SUCCESS ===");
                log.info("Ingested '{}' ({}) — {} chunks",
                        sourceName,
                        sourceType,
                        documents.size());

                state.setState(UploadState.READY);
            })
            .doOnError(e -> {
                log.error("=== INGESTION FAILED ===", e);

                state.setState(UploadState.FAILED);
            });
}

    private List<Document> chunkText(String text, String sourceType, String sourceName) {
        List<Document> chunks = new ArrayList<>();
        int totalLen = text.length();
        int cursor = 0;
        int chunkIndex = 0;
        String previousChunk = null;

        while (cursor < totalLen) {
            int end = Math.min(cursor + CHUNK_SIZE, totalLen);

            if (end < totalLen) {
                int snap = text.lastIndexOf('\n', end);
                if (snap <= cursor) {
                    snap = text.lastIndexOf(' ', end);
                }
                if (snap > cursor) {
                    end = snap;
                }
            }

            if (end <= cursor) {
                end = Math.min(cursor + CHUNK_SIZE, totalLen);
                if (end <= cursor) {
                    break;
                }
            }

            String chunk = text.substring(cursor, end).strip();

            if (!chunk.isEmpty() && !chunk.equals(previousChunk)) {
                Map<String, Object> meta = new HashMap<>();
                meta.put("sourceType", sourceType);
                meta.put("sourceName", sourceName);
                meta.put("chunkIndex", chunkIndex);
                chunks.add(new Document(chunk, meta));
                previousChunk = chunk;
                chunkIndex++;
            }

            int nextCursor = end - OVERLAP;
            if (nextCursor <= cursor) {
                nextCursor = end;
            }
            cursor = nextCursor;
        }

        return chunks;
    }
}
