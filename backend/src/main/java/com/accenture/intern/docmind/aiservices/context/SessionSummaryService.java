package com.accenture.intern.docmind.aiservices.context;

import com.accenture.intern.docmind.entity.Message;
import com.accenture.intern.docmind.entity.MessageRole;
import com.accenture.intern.docmind.entity.ModelName;
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
import java.util.List;

/**
 * Generates a short "executive summary" paragraph for a chat session, used at
 * the top of the PDF export (see PdfExportWorkerService / PdfGeneratorService).
 * <p>
 * Deliberately uses its own lightweight ChatClient call with a fast/cheap
 * model, same approach as SuggestedQuestionsService — this is a system-level
 * utility generation, not a user-facing chat turn.
 */
@Slf4j
@Service
public class SessionSummaryService {

    /** Keep the prompt input bounded — a PDF export of a very long session
     *  shouldn't blow up the summarization call. */
    private static final int MAX_CONVERSATION_CHARS = 18_000;

    private final ChatClient chatClient;
    private final String summaryPrompt;

    public SessionSummaryService(
            ChatClient.Builder chatClientBuilder,
            @Value("classpath:prompts/sessionsummaryprompt.st") Resource summaryPromptResource) throws IOException {
        this.chatClient = chatClientBuilder.build();
        this.summaryPrompt = StreamUtils.copyToString(summaryPromptResource.getInputStream(), StandardCharsets.UTF_8);
    }

    /**
     * @param messages the full chronological message history of the session
     * @return a short prose summary, or a graceful fallback string if the
     *         session has no real conversation yet or the LLM call fails
     */
    public Mono<String> summarize(List<Message> messages) {
        String conversation = buildConversationBlock(messages);
        if (conversation.isBlank()) {
            return Mono.just("This session does not contain any messages yet.");
        }

        String prompt = summaryPrompt.replace("{conversation}", truncate(conversation));

        GoogleGenAiChatOptions options = new GoogleGenAiChatOptions();
        options.setModel(ModelName.GEMINI_3_1_FLASH_LITE.getModelString());
        options.setTemperature(0.3);

        return Mono.fromCallable(() ->
                        chatClient.prompt()
                                .user(prompt)
                                .options(options)
                                .call()
                                .content()
                )
                .subscribeOn(Schedulers.boundedElastic())
                .map(String::trim)
                .doOnNext(s -> log.info("Generated session summary ({} chars)", s.length()))
                .onErrorResume(e -> {
                    log.warn("Failed to generate session summary, falling back to a generic line: {}", e.getMessage());
                    return Mono.just("A summary could not be generated automatically for this session.");
                });
    }

    private String buildConversationBlock(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (Message m : messages) {
            if (m.getContent() == null || m.getContent().isBlank()) continue;
            if (m.getContent().startsWith("[file upload:") || m.getContent().startsWith("[wikipedia link:")) {
                continue; // upload-anchor messages add noise, not conversational substance
            }
            String role = m.getRole() == MessageRole.USER ? "User" : "Assistant";
            sb.append(role).append(": ").append(m.getContent().trim()).append("\n");
        }
        return sb.toString().trim();
    }

    private String truncate(String text) {
        return text.length() > MAX_CONVERSATION_CHARS ? text.substring(0, MAX_CONVERSATION_CHARS) : text;
    }
}
