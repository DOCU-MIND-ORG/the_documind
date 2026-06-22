package com.accenture.intern.docmind.aiservices;

import com.accenture.intern.docmind.dto.chat.ContextResult;
import com.accenture.intern.docmind.dto.chat.EmbeddedDocument;
import com.accenture.intern.docmind.dto.chat.SessionUploadState;
import com.accenture.intern.docmind.dto.chat.UploadState;
import com.accenture.intern.docmind.entity.Message;
import com.accenture.intern.docmind.entity.MessageRole;
import com.accenture.intern.docmind.repository.MessageRepository;
import lombok.extern.slf4j.Slf4j;
import com.accenture.intern.docmind.service.SessionCacheService;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
public class ContextBuilderService {

    /**
     * How many of the most recent messages (user + assistant combined) to pull
     * into the prompt as short-term conversation history. Kept small on purpose:
     * this is just enough to resolve follow-ups / pronouns ("explain that more",
     * "what about the second one") without blowing up token usage on every turn.
     * 10 messages == last 5 user/assistant exchanges.
     */
    private static final int MAX_HISTORY_MESSAGES = 10;

    private final HybridRetrievalService hybridRetrievalService;
    private final SessionCacheService sessionCacheService;
    private final RerankService rerankService;
    private final MessageRepository messageRepository;
    private final String ragPrompt;
    private final String generalPrompt;

    public ContextBuilderService(
            HybridRetrievalService hybridRetrievalService,
            SessionCacheService sessionCacheService,
            RerankService rerankService,
            MessageRepository messageRepository,
            @Value("classpath:prompts/ragprompt.st") Resource ragPromptResource,
            @Value("classpath:prompts/generalprompt.st") Resource generalPromptResource) throws IOException {
        this.hybridRetrievalService = hybridRetrievalService;
        this.sessionCacheService = sessionCacheService;
        this.rerankService = rerankService;
        this.messageRepository = messageRepository;
        this.ragPrompt = StreamUtils.copyToString(ragPromptResource.getInputStream(), StandardCharsets.UTF_8);
        this.generalPrompt = StreamUtils.copyToString(generalPromptResource.getInputStream(), StandardCharsets.UTF_8);
    }

    public Mono<ContextResult> buildContext(String question, Long sessionId) {
        // Fetched once up front and reused by whichever branch below ends up
        // building the final prompt, so every chat turn — RAG or general — gets
        // the same short-term memory of the session.
        Mono<String> historyMono = fetchHistoryBlock(sessionId);

        SessionUploadState state = sessionCacheService.getState(sessionId);
        List<EmbeddedDocument> embeddedDocs = state == null ? null : state.getEmbeddedDocuments();
        boolean hasCachedDocs = embeddedDocs != null && !embeddedDocs.isEmpty();

        if (hasCachedDocs) {
            boolean isGeneralQuestion = isGeneralGreetingOrInquiry(question);
            if (isGeneralQuestion) {
                return fallbackRetrieve(question, sessionId, historyMono);
            }

            // Two cases where the most-recently-uploaded file(s) in THIS session
            // should be answered from directly, bypassing the corpus-wide search
            // entirely, instead of letting a brand-new upload compete on ranking
            // against every other document anyone has ever uploaded (which is
            // what was causing "who is the person in this document" / "what is
            // this" right after an upload to either come back empty or answer
            // about the wrong file):
            //   1. Ingestion is still in flight (EMBEDDING/INGESTING) — there's
            //      nothing else searchable for this file yet anyway.
            //   2. The question is clearly a deictic reference to "the document
            //      I just uploaded" ("what is this", "describe this file", ...).
            //      The user has told us exactly which document they mean, so we
            //      honor that instead of a ranked guess across the whole corpus.
            boolean stillIndexing = state.getState() == UploadState.EMBEDDING
                    || state.getState() == UploadState.INGESTING;
            boolean referencesUploadedDoc = isUploadedDocumentReferenceQuestion(question);

            if (stillIndexing || referencesUploadedDoc) {
                List<Document> selectedDocs = embeddedDocs.stream()
                        .map(EmbeddedDocument::getDocument)
                        .toList();

                int estimatedTokens = selectedDocs.stream()
                        .mapToInt(doc -> doc.getText().length() / 4)
                        .sum();
                log.info("Using {} local chunks ({} tokens) directly from session cache (stillIndexing={}, referencesUploadedDoc={})",
                        selectedDocs.size(), estimatedTokens, stillIndexing, referencesUploadedDoc);

                return historyMono.map(historyBlock -> new ContextResult(
                        selectedDocs.isEmpty() ? generalPrompt : ragPrompt,
                        buildPrompt(question, buildContextBlock(selectedDocs), historyBlock),
                        selectedDocs
                ));
            }
        }

        return fallbackRetrieve(question, sessionId, historyMono);
    }

    /**
     * Matches questions that clearly refer to "the document/file I just
     * uploaded" without necessarily naming it — e.g. "what is this", "describe
     * this document", "explain the given doc", "tell me about this file",
     * "summarize it". Whole-corpus ranked search has no way to resolve a
     * deictic "this"/"it"/"the given one" — it can only match words — so these
     * questions need to be routed straight at the most-recently-uploaded
     * file(s) in this session rather than rolled into the same ranking as every
     * other chunk in the shared corpus.
     * <p>
     * Deliberately keyword-set based rather than a single adjacency regex
     * (e.g. requiring "this document" as one phrase) — real phrasing puts
     * other words in between ("the given doc", "this uploaded file"), so the
     * check is: does the question contain *some* deictic/reference word, and
     * *some* generic document-type word, anywhere in it (not necessarily next
     * to each other)?
     */
    private static final java.util.Set<String> DEICTIC_WORDS = java.util.Set.of(
            "this", "that", "it", "given", "uploaded", "attached", "above", "provided");

    private static final java.util.Set<String> DOC_TYPE_WORDS = java.util.Set.of(
            "document", "documents", "doc", "docs", "file", "files", "pdf",
            "image", "picture", "photo", "attachment", "upload", "paper", "resume");

    private boolean isUploadedDocumentReferenceQuestion(String question) {
        String q = question.toLowerCase().trim();

        // "what is this", "what's this", "what is this about", "whats in this"
        if (q.matches("^(what('?s| is)?)\\s+(is\\s+)?this\\b.*")) {
            return true;
        }

        java.util.Set<String> words = java.util.Arrays.stream(q.split("[^a-z0-9]+"))
                .filter(w -> !w.isBlank())
                .collect(java.util.stream.Collectors.toSet());

        boolean hasDeictic = words.stream().anyMatch(DEICTIC_WORDS::contains);
        boolean hasDocType = words.stream().anyMatch(DOC_TYPE_WORDS::contains);

        // "explain the given doc", "who is the person in this document",
        // "this attached file" — deictic + doc-type word anywhere in the
        // question, regardless of order or what's between them.
        if (hasDeictic && hasDocType) {
            return true;
        }

        // "summarize it", "describe that", "tell me about this" — a
        // summarizing verb plus a deictic word, even with no doc-type noun at
        // all (the noun is implied: "it" == "the thing I just uploaded").
        boolean isSummarizingVerbPhrase = q.matches("^(describe|explain|summari[sz]e|analyse|analyze)\\b.*")
                || q.startsWith("tell me about");
        if (isSummarizingVerbPhrase && hasDeictic) {
            return true;
        }

        return false;
    }

    private Mono<ContextResult> fallbackRetrieve(String question, Long sessionId, Mono<String> historyMono) {
        long t0 = System.currentTimeMillis();
        return Mono.zip(hybridRetrievalService.retrieve(question, sessionId), historyMono)
                .flatMap(tuple -> {
                    long tRetrieveDone = System.currentTimeMillis();
                    log.info("[TIMING] hybrid retrieval (dense+keyword, parallel): {}ms", tRetrieveDone - t0);

                    List<Document> candidates = tuple.getT1();
                    String historyBlock = tuple.getT2();

                    // Whole-document retrieval already decided exactly which chunks belong
                    // in context (every chunk of the one matched source, in document
                    // order) - running them through the cross-encoder rerank would just
                    // truncate back down to topN=5 by relevance-to-query-wording, which is
                    // the same "missing sections" problem whole-document mode exists to
                    // fix. So this path bypasses rerank entirely and uses all of them.
                    boolean isWholeDocument = !candidates.isEmpty()
                            && Boolean.TRUE.equals(candidates.get(0).getMetadata().get("wholeDocumentMatch"));

                    if (isWholeDocument) {
                        log.info("[TIMING] whole-document mode: skipping rerank, using all {} chunks", candidates.size());
                        String contextBlock = buildContextBlock(candidates);
                        String augmentedPrompt = buildPrompt(question, contextBlock, historyBlock);
                        return Mono.just(new ContextResult(ragPrompt, augmentedPrompt, candidates));
                    }

                    // rerank() runs a CPU-bound ONNX cross-encoder loop (tens of
                    // sequential inferences). That must never run on a Netty event-loop
                    // thread - it would stall every other in-flight request sharing that
                    // loop, not just this one. Pushed onto boundedElastic, same as every
                    // other blocking call in this class (JPA history fetch, etc).
                    return Mono.fromCallable(() -> rerankService.rerank(question, candidates, 5))
                            .subscribeOn(Schedulers.boundedElastic())
                            .map(rerankedDocs -> {
                                long tRerankDone = System.currentTimeMillis();
                                log.info("[TIMING] rerank ({} candidates -> {}): {}ms",
                                        candidates.size(), rerankedDocs.size(), tRerankDone - tRetrieveDone);
                                log.info("[TIMING] total buildContext: {}ms", tRerankDone - t0);

                                String contextBlock = buildContextBlock(rerankedDocs);
                                String augmentedPrompt = buildPrompt(question, contextBlock, historyBlock);
                                String systemPrompt = rerankedDocs.isEmpty() ? generalPrompt : ragPrompt;
                                return new ContextResult(systemPrompt, augmentedPrompt, rerankedDocs);
                            });
                });
    }

    /**
     * Pulls the last MAX_HISTORY_MESSAGES messages for the session (run on a
     * blocking-friendly scheduler since it's a JPA call) and formats them into a
     * simple "Role: text" transcript, oldest first. Returns an empty string if the
     * session has no prior turns yet (first message of the conversation).
     */
    private Mono<String> fetchHistoryBlock(Long sessionId) {
        return Mono.fromCallable(() -> {
                    List<Message> recent = messageRepository
                            .findTop10BySession_SessionIdOrderByCreatedAtDesc(sessionId);
                    if (recent.isEmpty()) {
                        return "";
                    }
                    List<Message> chronological = new ArrayList<>(recent);
                    Collections.reverse(chronological);

                    StringBuilder sb = new StringBuilder();
                    for (Message m : chronological) {
                        String role = m.getRole() == MessageRole.USER ? "User" : "Assistant";
                        String content = m.getContent() == null ? "" : m.getContent().trim();
                        if (content.isEmpty()) {
                            continue;
                        }
                        sb.append(role).append(": ").append(content).append("\n");
                    }
                    return sb.toString().trim();
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    log.warn("Failed to load conversation history for session {}: {}", sessionId, e.getMessage());
                    return Mono.just("");
                });
    }

    private boolean isGeneralGreetingOrInquiry(String question) {
        String q = question.toLowerCase();
        return q.matches("^(hi|hello|hey|greetings|how are you|what can you do).*");
    }

    private String buildContextBlock(List<Document> docs) {
        if (docs.isEmpty()) {
            return "No relevant documents found.";
        }

        StringBuilder sb = new StringBuilder();
        int index = 1;
        for (Document doc : docs) {
            String name = (String) doc.getMetadata().getOrDefault("sourceName", "unknown");
            String type = (String) doc.getMetadata().getOrDefault("sourceType", "");
            int chunk = ((Number) doc.getMetadata().getOrDefault("chunkIndex", 0)).intValue();

            sb.append(String.format("<CITATION id=\"%d\">\nSource: %s | Type: %s | Chunk: %d\n%s\n</CITATION>\n\n",
                    index++, name, type, chunk, doc.getText()));
        }
        return sb.toString().trim();
    }

    private String buildPrompt(String question, String context, String historyBlock) {
        String historySection = (historyBlock == null || historyBlock.isBlank())
                ? ""
                : """
                  CONVERSATION HISTORY (most recent turns in this session, oldest first — use this only to understand what the user is referring to, e.g. follow-ups, pronouns, "the previous one"; it is not a source of facts):
                  %s

                  ---

                  """.formatted(historyBlock);

        return """
                %sCONTEXT (from uploaded documents):
                %s

                ---

                TASK: %s
                """.formatted(historySection, context, question);
    }
}
