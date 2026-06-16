package com.accenture.intern.docmind.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class VectorStoreService {

    private static final int CHUNK_SIZE = 600;
    private static final int OVERLAP    = 80;
    private static final int BATCH_SIZE = 10;

    private final VectorStore vectorStore;

    public VectorStoreService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public void ingest(String text, String sourceType, String sourceName) {
        if (text == null || text.isBlank()) {
            log.warn("Skipping ingest — empty text for '{}'", sourceName);
            return;
        }

        int totalLen = text.length();
        int cursor = 0;
        int chunkIndex = 0;
        int batchCount = 0;
        String previousChunk = null;
        List<Document> batch = new ArrayList<>(BATCH_SIZE);

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
                batch.add(new Document(chunk, meta));
                previousChunk = chunk;
                chunkIndex++;
            }

            if (batch.size() >= BATCH_SIZE) {
                vectorStore.add(batch);
                batchCount++;
                log.debug("Flushed batch {} for '{}'", batchCount, sourceName);
                batch = new ArrayList<>(BATCH_SIZE);
            }

            int nextCursor = end - OVERLAP;
            if (nextCursor <= cursor) {
                nextCursor = end;
            }
            cursor = nextCursor;
        }

        if (!batch.isEmpty()) {
            vectorStore.add(batch);
            batchCount++;
        }

        log.info("Ingested '{}' ({}) — {} chunks in {} batches", sourceName, sourceType, chunkIndex, batchCount);
    }

    public List<Document> retrieve(String query, int topK) {
        return vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(query)
                        .topK(topK)
                        .similarityThreshold(0.3)
                        .build()
        );
    }
}
