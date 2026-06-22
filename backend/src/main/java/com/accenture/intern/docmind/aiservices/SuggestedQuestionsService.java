package com.accenture.intern.docmind.aiservices;

import com.accenture.intern.docmind.dto.chat.SessionUploadState;
import com.accenture.intern.docmind.entity.Message;
import com.accenture.intern.docmind.entity.MessageRole;
import com.accenture.intern.docmind.entity.ModelName;
import com.accenture.intern.docmind.repository.MessageRepository;
import com.accenture.intern.docmind.service.SessionCacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Generates short "you might want to ask" starter/follow-up questions shown in
 * the chat UI.
 * <p>
 * Two entry points feed the same SessionUploadState cache (and therefore the
 * same GET /api/sessions/{id}/suggested-questions polling endpoint):
 * <ul>
 *   <li>{@link #generateForSession(Long)} — grounded in uploaded documents,
 *       triggered by EmbeddingService right after ingestion finishes.</li>
 *   <li>{@link #triggerFollowUpForSession(Long)} — grounded in the conversation
 *       so far (plus any ingested documents), triggered by ChatService after
 *       every assistant response so suggestions stay relevant turn-by-turn.</li>
 * </ul>
 * Deliberately uses its own lightweight ChatClient call rather than going through
 * ModelFactory/user chat preferences: this is a system-level utility generation,
 * not a user-facing chat turn, so it always uses a fast/cheap model
 * (GEMINI_2_5_FLASH_LITE) regardless of what the user has selected for chat.
 */
@Slf4j
@Service
public class SuggestedQuestionsService {

    /** Keep the prompt input bounded — we only need enough text to ground 3
     *  questions, not the entire corpus, and large prompts cost more and add
     *  latency for no real benefit here. */
    private static final int MAX_CONTENT_CHARS = 12_000;

    /** Last N messages (user + assistant combined) used to ground follow-up
     *  question generation. Smaller than ContextBuilderService's chat-context
     *  window on purpose — we just need the recent thread of conversation, not
     *  a long history, to suggest a sensible "what next". */
    private static final int MAX_CONVERSATION_MESSAGES = 6;

    private static final int QUESTION_COUNT = 3;

    private final ChatClient chatClient;
    private final SessionCacheService sessionCacheService;
    private final MessageRepository messageRepository;
    private final String suggestedQuestionsPrompt;
    private final String followUpQuestionsPrompt;

    public SuggestedQuestionsService(
            ChatClient.Builder chatClientBuilder,
            SessionCacheService sessionCacheService,
            MessageRepository messageRepository,
            @Value("classpath:prompts/suggestedquestionsprompt.st") Resource promptResource,
            @Value("classpath:prompts/followupquestionsprompt.st") Resource followUpPromptResource) throws IOException {
        this.chatClient = chatClientBuilder.build();
        this.sessionCacheService = sessionCacheService;
        this.messageRepository = messageRepository;
        this.suggestedQuestionsPrompt = StreamUtils.copyToString(promptResource.getInputStream(), StandardCharsets.UTF_8);
        this.followUpQuestionsPrompt = StreamUtils.copyToString(followUpPromptResource.getInputStream(), StandardCharsets.UTF_8);
    }

    // =========================================================================
    // Post-upload starter questions (existing behaviour, grounded in documents)
    // =========================================================================

    /**
     * Convenience entry point used by EmbeddingService once ingestion finishes:
     * pulls together every document ingested in this session so far (not just the
     * latest upload) from the session's cached upload state, and generates
     * questions from the combined text.
     */
    public Mono<List<String>> generateForSession(Long sessionId) {
        SessionUploadState state = sessionCacheService.getState(sessionId);
        List<String> documentTexts = state == null ? List.of() : state.getIngestedDocumentTexts();
        return generate(documentTexts);
    }

    /**
     * @param documentTexts raw text of every document ingested in the session so
     *                      far (combined, not just the latest upload)
     */
    public Mono<List<String>> generate(List<String> documentTexts) {
        if (documentTexts == null || documentTexts.isEmpty()) {
            return Mono.just(List.of());
        }

        String combined = truncate(String.join("\n\n---\n\n", documentTexts));
        String prompt = suggestedQuestionsPrompt.replace("{content}", combined);
        return callModel(prompt, "starter");
    }

    // =========================================================================
    // Post-response follow-up questions (new, grounded in the conversation)
    // =========================================================================

    /**
     * Fire-and-forget: regenerates this session's suggested questions from the
     * conversation so far (latest user/assistant turns), optionally grounded by
     * any documents already ingested in the session, and writes the result into
     * the same {@link SessionUploadState} cache used by the
     * GET /api/sessions/{id}/suggested-questions polling endpoint.
     * <p>
     * Intended to be called after every assistant response (see ChatService),
     * not chained into the chat stream itself — a slow or failing LLM call here
     * must never delay or break the chat response. The frontend keeps polling
     * the existing suggested-questions endpoint to pick up the refreshed list.
     */
    public void triggerFollowUpForSession(Long sessionId) {
        SessionUploadState state = sessionCacheService.getOrCreateState(sessionId);
        state.setQuestionsStatus(SessionUploadState.SuggestedQuestionsStatus.GENERATING);

        generateFollowUp(sessionId)
                .subscribe(
                        questions -> {
                            state.setSuggestedQuestions(questions);
                            state.setQuestionsStatus(questions.isEmpty()
                                    ? SessionUploadState.SuggestedQuestionsStatus.FAILED
                                    : SessionUploadState.SuggestedQuestionsStatus.READY);
                        },
                        e -> {
                            log.warn("Follow-up question generation errored for session {}: {}", sessionId, e.getMessage());
                            state.setQuestionsStatus(SessionUploadState.SuggestedQuestionsStatus.FAILED);
                        }
                );
    }

    private Mono<List<String>> generateFollowUp(Long sessionId) {
        return Mono.fromCallable(() -> buildConversationBlock(sessionId))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(conversationBlock -> {
                    if (conversationBlock.isBlank()) {
                        // Nothing to ground a follow-up in yet (shouldn't normally
                        // happen since this runs after a response has been saved).
                        return Mono.just(List.<String>of());
                    }

                    SessionUploadState state = sessionCacheService.getState(sessionId);
                    List<String> documentTexts = state == null ? List.of() : state.getIngestedDocumentTexts();
                    String content = documentTexts.isEmpty()
                            ? "(none — base the questions on the conversation alone)"
                            : truncate(String.join("\n\n---\n\n", documentTexts));

                    String prompt = followUpQuestionsPrompt
                            .replace("{conversation}", conversationBlock)
                            .replace("{content}", content);

                    return callModel(prompt, "follow-up");
                });
    }

    /**
     * Builds a simple "Role: text" transcript of the last
     * MAX_CONVERSATION_MESSAGES messages in the session, oldest first.
     */
    private String buildConversationBlock(Long sessionId) {
        List<Message> recent = messageRepository.findTop10BySession_SessionIdOrderByCreatedAtDesc(sessionId);
        if (recent.isEmpty()) {
            return "";
        }

        List<Message> chronological = new ArrayList<>(recent);
        Collections.reverse(chronological);
        if (chronological.size() > MAX_CONVERSATION_MESSAGES) {
            chronological = chronological.subList(
                    chronological.size() - MAX_CONVERSATION_MESSAGES, chronological.size());
        }

        StringBuilder sb = new StringBuilder();
        for (Message m : chronological) {
            String role = m.getRole() == MessageRole.USER ? "User" : "Assistant";
            String text = m.getContent() == null ? "" : m.getContent().trim();
            if (text.isEmpty()) {
                continue;
            }
            sb.append(role).append(": ").append(text).append("\n");
        }
        return sb.toString().trim();
    }

    private String truncate(String text) {
        return text.length() > MAX_CONTENT_CHARS ? text.substring(0, MAX_CONTENT_CHARS) : text;
    }

    // =========================================================================
    // Shared LLM call + parsing
    // =========================================================================

    private Mono<List<String>> callModel(String prompt, String kind) {
        GoogleGenAiChatOptions options = new GoogleGenAiChatOptions();
        options.setModel(ModelName.GEMINI_3_1_FLASH_LITE.getModelString());
        options.setTemperature(0.4); // a little variety, but still grounded

        return Mono.fromCallable(() ->
                        chatClient.prompt()
                                .user(prompt)
                                .options(options)
                                .call()
                                .content()
                )
                .subscribeOn(Schedulers.boundedElastic())
                .map(this::parseQuestions)
                .doOnNext(qs -> log.info("Generated {} {} questions", qs.size(), kind))
                .onErrorResume(e -> {
                    log.error("Failed to generate {} questions, returning empty list", kind, e);
                    return Mono.just(List.of());
                });
    }

    /**
     * The model is instructed to return exactly one question per line with no
     * numbering/bullets, but we parse defensively anyway in case it adds them —
     * stripping common list markers rather than trusting the format blindly.
     */
    private List<String> parseQuestions(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }

        return Arrays.stream(raw.split("\\r?\\n"))
                .map(line -> line.replaceFirst("^[\\s\\-*•\\d.)\"']+", "").trim())
                .filter(line -> !line.isBlank())
                .limit(QUESTION_COUNT)
                .collect(Collectors.toList());
    }
}
