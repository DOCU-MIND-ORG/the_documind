package com.accenture.intern.docmind.aiservices;

import com.accenture.intern.docmind.entity.DocumentChunk;
import com.accenture.intern.docmind.repository.DocumentChunkRepository;
import lombok.extern.slf4j.Slf4j;
import com.accenture.intern.docmind.service.SessionCacheService;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import com.accenture.intern.docmind.dto.chat.EmbeddedDocument;
import com.accenture.intern.docmind.dto.chat.SessionUploadState;
import com.accenture.intern.docmind.dto.chat.UploadState;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class EmbeddingService {

    private static final int CHUNK_SIZE = 1000;
    private static final int OVERLAP    = 200;

    /** Minimum acceptable chunk length when snapping to a paragraph/sentence
     *  boundary in findBoundary() — see that method's javadoc. */
    private static final int MIN_CHUNK_CHARS = (int) (CHUNK_SIZE * 0.4);

    private final VectorStoreService vectorStoreService;
    private final SessionCacheService sessionCacheService;
    private final DocumentChunkRepository documentChunkRepository;
    private final SuggestedQuestionsService suggestedQuestionsService;

    public EmbeddingService(VectorStoreService vectorStoreService,
                             SessionCacheService sessionCacheService,
                             DocumentChunkRepository documentChunkRepository,
                             SuggestedQuestionsService suggestedQuestionsService) {
        this.vectorStoreService = vectorStoreService;
        this.sessionCacheService = sessionCacheService;
        this.documentChunkRepository = documentChunkRepository;
        this.suggestedQuestionsService = suggestedQuestionsService;
    }

    public Mono<Void> processAndIngest(String text, String sourceType, String sourceName, Long sessionId) {
        return processAndIngest(text, sourceType, sourceName, sessionId, null, null);
    }

    public Mono<Void> processAndIngest(String text, String sourceType, String sourceName, Long sessionId, String imageUrl) {
        return processAndIngest(text, sourceType, sourceName, sessionId, imageUrl, null);
    }

    /**
     * @param imageUrl  set when this text IS a Gemini Vision description of an
     *                  image (standalone IMAGE attachment, or an image extracted
     *                  from inside a PDF) - the frontend renders this inline as
     *                  the cited image itself.
     * @param sourceUrl set when this text came from a document that's itself
     *                  viewable/downloadable (currently: a PDF's own Cloudinary
     *                  URL) - the frontend offers it as "open the source document"
     *                  rather than rendering it inline. Independent of imageUrl;
     *                  a plain PDF text chunk has sourceUrl but no imageUrl.
     */
    public Mono<Void> processAndIngest(String text, String sourceType, String sourceName, Long sessionId, String imageUrl, String sourceUrl) {

        log.info("=== INGESTION STARTED ===");
        log.info("SessionId={}", sessionId);
        log.info("SourceName={}", sourceName);
        log.info("SourceType={}", sourceType);
        log.info("TextLength={}", text == null ? 0 : text.length());
        log.info("ImageUrl={}", imageUrl);
        log.info("SourceUrl={}", sourceUrl);

        if (text == null || text.isBlank()) {
            log.warn("Skipping ingest — empty text for '{}'", sourceName);
            return Mono.empty();
        }

        SessionUploadState state = sessionCacheService.getOrCreateState(sessionId);
        state.setState(UploadState.EMBEDDING);
        state.addIngestedDocumentText(text);

        String contentHash = hashContent(text);

        // Whole-document dedup: a SHA-256 of the full raw text identifies "is this
        // the exact same document as something already in the corpus" with a single
        // indexed lookup, before spending any time chunking or embedding. This only
        // catches byte-for-byte (or whitespace-for-whitespace) identical re-uploads —
        // a resume re-saved with one line changed will hash differently and ingest
        // normally, which is the intended, narrower scope here.
        return Mono.fromCallable(() -> documentChunkRepository.findByContentHash(contentHash))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(existingChunks -> {
                    if (!existingChunks.isEmpty()) {
                        return reboostExistingChunks(existingChunks, sourceName, sessionId, state);
                    }
                    return doIngest(text, sourceType, sourceName, sessionId, imageUrl, sourceUrl, contentHash, state);
                });
    }

    /**
     * The exact same content already exists in the corpus (possibly uploaded
     * into a different session entirely). Rather than re-chunk and re-embed it
     * - wasted work for content that's already searchable - re-point its
     * existing chunks at THIS session, both in Postgres (the sessionId column)
     * and in Pinecone (the mirrored sessionId metadata on each vector).
     * <p>
     * Why this matters: HybridRetrievalService boosts a chunk's relevance score
     * by SESSION_BOOST_MULTIPLIER only when the chunk's stored sessionId matches
     * the CURRENT chat session (see fuse()). A chunk permanently tied to
     * whichever session first uploaded it would never get that boost in any
     * later session, even though - from the user's point of view - they just
     * uploaded this exact file right here, right now, and reasonably expect it
     * to behave like any other fresh upload in this conversation. Re-pointing
     * sessionId to the most recent uploading session makes that true, without
     * creating duplicate rows/vectors for content that hasn't changed.
     * <p>
     * This intentionally moves the chunks rather than keeping a multi-session
     * membership list - simpler data model, and matches the actual request:
     * treat the most recent uploader's session as "the" session for the boost.
     */
    private Mono<Void> reboostExistingChunks(List<DocumentChunk> existingChunks, String sourceName, Long sessionId, SessionUploadState state) {
        return Mono.fromRunnable(() -> {
                    String existingSourceName = existingChunks.get(0).getSourceName();
                    log.info("'{}' matches existing content ('{}', {} chunks) — re-pointing to session {} for relevance boost instead of re-ingesting",
                            sourceName, existingSourceName, existingChunks.size(), sessionId);

                    // 1. Update the Postgres rows directly (sessionId is the column the
                    //    keyword-search path's chunks carry into fuse()'s boost check).
                    for (DocumentChunk chunk : existingChunks) {
                        chunk.setSessionId(sessionId);
                    }
                    documentChunkRepository.saveAll(existingChunks);

                    // 2. Mirror the same change into Pinecone. Re-add() with the same
                    //    vectorId (Document.id) overwrites the existing record in place
                    //    (same text, updated metadata) rather than creating a duplicate -
                    //    see IntegratedPineconeVectorStore.add(), which upserts by _id.
                    List<Document> updatedVectors = existingChunks.stream()
                            .sorted(java.util.Comparator.comparing(c -> c.getChunkIndex() == null ? 0 : c.getChunkIndex()))
                            .map(chunk -> {
                                Map<String, Object> metadata = new HashMap<>();
                                metadata.put("sourceName", chunk.getSourceName());
                                metadata.put("sourceType", chunk.getSourceType());
                                metadata.put("chunkIndex", chunk.getChunkIndex() == null ? 0 : chunk.getChunkIndex());
                                metadata.put("sessionId", sessionId);
                                if (chunk.getImageUrl() != null && !chunk.getImageUrl().isBlank()) {
                                    metadata.put("imageUrl", chunk.getImageUrl());
                                    metadata.put("isImage", true);
                                }
                                if (chunk.getSourceUrl() != null && !chunk.getSourceUrl().isBlank()) {
                                    metadata.put("sourceUrl", chunk.getSourceUrl());
                                }
                                return new Document(chunk.getVectorId(), chunk.getContent(), metadata);
                            })
                            .collect(java.util.stream.Collectors.toList());

                    vectorStoreService.ingestDocuments(updatedVectors).subscribe(
                            v -> {},
                            e -> log.error("Failed to re-point Pinecone metadata for duplicate of '{}'", sourceName, e));

                    // 3. Populate the session's local chunk cache exactly like a fresh
                    //    upload would (see doIngest below). Without this, re-uploading
                    //    content that already exists ANYWHERE in the shared corpus
                    //    (extremely common while testing with the same sample files)
                    //    never populates embeddedDocuments for this session, so
                    //    ContextBuilderService's "answer from what was just uploaded"
                    //    fast path silently has nothing to use and falls through to
                    //    corpus-wide search regardless of how the question is phrased.
                    List<EmbeddedDocument> embeddedDocs = updatedVectors.stream()
                            .map(doc -> new EmbeddedDocument(doc, new float[0]))
                            .toList();
                    state.setEmbeddedDocuments(embeddedDocs);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .doOnTerminate(() -> {
                    state.setState(UploadState.READY);
                    triggerSuggestedQuestionGeneration(sessionId, state);
                })
                .then();
    }

    private Mono<Void> doIngest(String text, String sourceType, String sourceName, Long sessionId,
                                 String imageUrl, String sourceUrl, String contentHash, SessionUploadState state) {
        log.info("Chunking document...");
        List<Document> documents = chunkText(text, sourceType, sourceName, sessionId, imageUrl, sourceUrl);
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

        Mono<Void> pineconeIngest = vectorStoreService.ingestDocuments(documents);
        Mono<Void> postgresIngest = saveChunksForKeywordSearch(documents, sessionId, sourceType, sourceName, contentHash);

        return Mono.when(pineconeIngest, postgresIngest)
                .doOnSubscribe(sub ->
                        log.info("Pinecone + Postgres ingestion subscribed"))
                .doOnSuccess(v -> {
                    log.info("INGESTION SUCCESS");
                    log.info("Ingested '{}' ({}) — {} chunks",
                            sourceName,
                            sourceType,
                            documents.size());

                    state.setState(UploadState.READY);
                    triggerSuggestedQuestionGeneration(sessionId, state);
                })
                .doOnError(e -> {
                    log.error("=== INGESTION FAILED ===", e);

                    state.setState(UploadState.FAILED);
                });
    }

    /**
     * SHA-256 hex digest of the document's raw text, normalized (trimmed, internal
     * whitespace collapsed) so two uploads that differ only in incidental whitespace
     * — e.g. a PDF re-exported with different line wrapping but identical actual
     * content — still hash identically. Not intended to catch real content edits;
     * any visible text difference should and will produce a different hash.
     */
    private String hashContent(String text) {
        String normalized = text.trim().replaceAll("\\s+", " ");
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(normalized.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hashBytes.length * 2);
            for (byte b : hashBytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-256 is a standard JDK algorithm guaranteed present on every
            // conforming JVM - this branch is unreachable in practice.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Kicks off question generation in the background once ingestion is READY.
     * Deliberately fire-and-forget (subscribed here, not chained into the returned
     * Mono) so a slow or failing LLM call never delays or breaks the ingestion
     * response — the suggested-questions endpoint is polled separately by the
     * frontend and just reports GENERATING until this finishes.
     */
    private void triggerSuggestedQuestionGeneration(Long sessionId, SessionUploadState state) {
        state.setQuestionsStatus(SessionUploadState.SuggestedQuestionsStatus.GENERATING);
        suggestedQuestionsService.generateForSession(sessionId)
                .subscribe(
                        questions -> {
                            state.setSuggestedQuestions(questions);
                            state.setQuestionsStatus(questions.isEmpty()
                                    ? SessionUploadState.SuggestedQuestionsStatus.FAILED
                                    : SessionUploadState.SuggestedQuestionsStatus.READY);
                        },
                        e -> {
                            log.warn("Suggested-question generation errored for session {}: {}", sessionId, e.getMessage());
                            state.setQuestionsStatus(SessionUploadState.SuggestedQuestionsStatus.FAILED);
                        }
                );
    }

    /**
     * Mirrors each chunk into Postgres so HybridRetrievalService can run keyword
     * (BM25-style) search alongside the Pinecone dense search. This is the lexical
     * half of hybrid retrieval — see DocumentChunkRepository.keywordSearch.
     */
    private Mono<Void> saveChunksForKeywordSearch(List<Document> documents, Long sessionId,
                                                   String sourceType, String sourceName, String contentHash) {
        return Mono.fromRunnable(() -> {
                    List<DocumentChunk> rows = documents.stream()
                            .map(doc -> DocumentChunk.builder()
                                    .vectorId(doc.getId())
                                    .sessionId(sessionId)
                                    .content(doc.getText())
                                    .sourceName(sourceName)
                                    .sourceType(sourceType)
                                    .contentHash(contentHash)
                                    .chunkIndex(((Number) doc.getMetadata().getOrDefault("chunkIndex", 0)).intValue())
                                    .imageUrl((String) doc.getMetadata().get("imageUrl"))
                                    .sourceUrl((String) doc.getMetadata().get("sourceUrl"))
                                    .createdAt(LocalDateTime.now())
                                    .build())
                            .toList();
                    documentChunkRepository.saveAll(rows);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    /**
     * Splits text into ~CHUNK_SIZE-character windows with OVERLAP carried between
     * consecutive chunks — same shape as before — but now snaps chunk boundaries to
     * paragraph and sentence breaks instead of the nearest space/newline. The old
     * approach could (and regularly did) cut a chunk off mid-sentence or mid-table
     * row, which hurts both the embedding (it represents a fragment, not a coherent
     * idea) and the cross-encoder's ability to judge relevance.
     * <p>
     * Strategy, in priority order, for where to end a chunk:
     * 1. The last paragraph break (blank line) before the CHUNK_SIZE budget — keeps
     *    whole paragraphs together whenever the budget allows it.
     * 2. The last sentence boundary (. ! ? followed by whitespace) before the
     *    budget — used when a single paragraph is longer than CHUNK_SIZE.
     * 3. The old space/newline snap — only as a last resort, e.g. for text with no
     *    punctuation at all (a single very long line, code, etc.).
     */
    private List<Document> chunkText(String text, String sourceType, String sourceName, Long sessionId, String imageUrl, String sourceUrl) {
        List<Document> chunks = new ArrayList<>();
        int totalLen = text.length();
        int cursor = 0;
        int chunkIndex = 0;
        String previousChunk = null;

        while (cursor < totalLen) {
            int end = Math.min(cursor + CHUNK_SIZE, totalLen);

            if (end < totalLen) {
                int snap = findBoundary(text, cursor, end);
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
                meta.put("sessionId", sessionId);
                if (imageUrl != null && !imageUrl.isBlank()) {
                    meta.put("imageUrl", imageUrl);
                    meta.put("isImage", true);
                }
                if (sourceUrl != null && !sourceUrl.isBlank()) {
                    meta.put("sourceUrl", sourceUrl);
                }

                // Explicit, stable id (rather than letting Pinecone/Spring AI auto-generate
                // one) so the same chunk can be referenced from the Postgres side for fusion.
                String vectorId = UUID.randomUUID().toString();
                chunks.add(new Document(vectorId, chunk, meta));

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

    /**
     * Finds the best place to end a chunk that starts at {@code cursor} and would
     * otherwise be cut off at {@code limit}. Tries paragraph break, then sentence
     * end, then falls back to the original whitespace-snapping behavior. A
     * candidate is only accepted if it leaves a chunk of at least MIN_CHUNK_CHARS —
     * otherwise a stray blank line or sentence end very early in the window would
     * produce a tiny chunk and barely advance the cursor, which both wastes chunks
     * and (in pathological text with many blank lines close together) risks an
     * extremely slow crawl through the document.
     */
    private int findBoundary(String text, int cursor, int limit) {
        int minEnd = cursor + MIN_CHUNK_CHARS;

        int paragraphBreak = text.lastIndexOf("\n\n", limit);
        if (paragraphBreak >= minEnd) {
            return paragraphBreak + 2; // include the break itself, point past it
        }

        int sentenceEnd = lastSentenceEnd(text, cursor, limit);
        if (sentenceEnd >= minEnd) {
            return sentenceEnd;
        }

        int newlineSnap = text.lastIndexOf('\n', limit);
        if (newlineSnap >= minEnd) {
            return newlineSnap;
        }

        int spaceSnap = text.lastIndexOf(' ', limit);
        if (spaceSnap >= minEnd) {
            return spaceSnap;
        }

        // Nothing met the minimum-size bar — better to take the hard limit than to
        // produce a tiny chunk or stall, same as the original implementation's
        // ultimate fallback.
        return limit;
    }

    /**
     * Scans backward from {@code limit} for the end of a sentence — '.', '!', or
     * '?' immediately followed by whitespace (or end of the searched range) — and
     * returns the index just past the punctuation + whitespace, so the next chunk
     * starts cleanly on a new sentence.
     */
    private int lastSentenceEnd(String text, int cursor, int limit) {
        for (int i = Math.min(limit, text.length()) - 1; i > cursor; i--) {
            char c = text.charAt(i);
            if ((c == '.' || c == '!' || c == '?')
                    && (i + 1 >= text.length() || Character.isWhitespace(text.charAt(i + 1)))) {
                return i + 1;
            }
        }
        return -1;
    }
}
