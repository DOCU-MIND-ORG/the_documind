package com.accenture.intern.docmind.aiservices;

import com.accenture.intern.docmind.dto.chat.ChatRequest;
import com.accenture.intern.docmind.dto.chat.ContextResult;
import com.accenture.intern.docmind.dto.chat.RagResponse;
import com.accenture.intern.docmind.entity.Message;
import com.accenture.intern.docmind.entity.MessageRole;
import com.accenture.intern.docmind.entity.MessageStatus;
import com.accenture.intern.docmind.entity.Session;
import com.accenture.intern.docmind.repository.MessageRepository;
import com.accenture.intern.docmind.repository.SessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ChatService {

    private final ChatClient chatClient;
    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;
    private final ContextBuilderService contextBuilderService;
    private final CitationService citationService;
    private final ObjectMapper objectMapper;
    private final ModelFactory modelFactory;
    private final SuggestedQuestionsService suggestedQuestionsService;

    public ChatService(ChatClient.Builder chatClientBuilder,
                       SessionRepository sessionRepository,
                       MessageRepository messageRepository,
                       ContextBuilderService contextBuilderService,
                       CitationService citationService,
                       ObjectMapper objectMapper,
                       ModelFactory modelFactory,
                       SuggestedQuestionsService suggestedQuestionsService) {
        this.chatClient = chatClientBuilder.build();
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.contextBuilderService = contextBuilderService;
        this.citationService = citationService;
        this.objectMapper = objectMapper;
        this.modelFactory = modelFactory;
        this.suggestedQuestionsService = suggestedQuestionsService;
    }

    public Flux<ServerSentEvent<String>> streamChat(Long sessionId, ChatRequest request) {

        long requestStart = System.currentTimeMillis();

        reactor.core.publisher.Mono<Session> sessionMono = reactor.core.publisher.Mono.fromCallable(() ->
                sessionRepository.findById(sessionId)
                        .orElseThrow(() -> new RuntimeException("Session not found"))
        ).subscribeOn(Schedulers.boundedElastic());

        reactor.core.publisher.Mono<ContextResult> contextMono = contextBuilderService.buildContext(request.getMessage(), sessionId);

        return reactor.core.publisher.Mono.zip(sessionMono, contextMono)
                .flatMapMany(tuple -> {
                    log.info("[TIMING] session+context ready: {}ms since request start", System.currentTimeMillis() - requestStart);

                    Session session = tuple.getT1();
                    ContextResult contextResult = tuple.getT2();

                    reactor.core.publisher.Mono<Message> saveUserMsg = reactor.core.publisher.Mono.fromCallable(() ->
                            messageRepository.save(Message.builder()
                                    .session(session)
                                    .role(MessageRole.USER)
                                    .content(request.getMessage())
                                    .status(MessageStatus.COMPLETE)
                                    .citationsJson(null)
                                    .createdAt(LocalDateTime.now())
                                    .build())
                    ).subscribeOn(Schedulers.boundedElastic());

                    StringBuilder aiResponseBuilder = new StringBuilder();
                    java.util.concurrent.atomic.AtomicBoolean firstTokenLogged = new java.util.concurrent.atomic.AtomicBoolean(false);

                    // Computed once, lazily, the moment the full response text is known
                    // (doOnComplete fires before citationsStream runs, since citationsStream
                    // is sequenced after tokenStream by Flux.concat below) - never read
                    // before that point. Citations can only be known once the LLM has
                    // finished choosing which [CITE:n] tags to actually write, which is why
                    // this can't be computed up front the way the old code did: extracting
                    // from contextResult.documents() before generation captured every
                    // retrieved chunk, not just the ones the model ended up citing.
                    java.util.concurrent.atomic.AtomicReference<String> finalCitationsJsonRef =
                            new java.util.concurrent.atomic.AtomicReference<>();

                    org.springframework.ai.google.genai.GoogleGenAiChatOptions options = modelFactory.getChatOptions(session.getUser(), request.getModel());
                    String finalSystemPrompt = modelFactory.injectResponseStyle(session.getUser(), contextResult.systemPrompt());

                    // Persist the user's message concurrently with the LLM call instead of
                    // gating the stream on it. The DB write and the first LLM token have no
                    // dependency on each other - waiting for one before starting the other
                    // only adds the write's latency (a network round trip to Postgres) onto
                    // every single turn for no benefit. We still want the write to happen
                    // and to be visible to error logs/handlers, so it's subscribed
                    // independently here rather than dropped.
                    saveUserMsg.subscribe(
                            saved -> {},
                            err -> log.error("Failed to save user message for session {}", sessionId, err)
                    );

                    Flux<ServerSentEvent<String>> tokenStream = chatClient.prompt()
                            .system(finalSystemPrompt)
                            .user(contextResult.prompt())
                            .options(options)
                            .stream()
                            .content()
                            .doOnNext(token -> {
                                if (firstTokenLogged.compareAndSet(false, true)) {
                                    log.info("[TIMING] first LLM token: {}ms since request start", System.currentTimeMillis() - requestStart);
                                }
                                if (token != null) aiResponseBuilder.append(token);
                            })
                            .doOnComplete(() -> {
                                String citationsJson = computeCitationsJson(contextResult, aiResponseBuilder.toString());
                                finalCitationsJsonRef.set(citationsJson);

                                reactor.core.publisher.Mono.fromCallable(() -> messageRepository.save(Message.builder()
                                        .session(session)
                                        .role(MessageRole.ASSISTANT)
                                        .content(aiResponseBuilder.toString())
                                        .status(MessageStatus.COMPLETE)
                                        .citationsJson(citationsJson)
                                        .createdAt(LocalDateTime.now())
                                        .build()))
                                    .subscribeOn(Schedulers.boundedElastic())
                                    .doOnSuccess(saved ->
                                            // Fire-and-forget: refresh the session's suggested
                                            // questions based on the conversation so far, now that
                                            // this turn is persisted. The frontend keeps polling the
                                            // existing /suggested-questions endpoint, which now
                                            // updates after every response, not just after uploads.
                                            suggestedQuestionsService.triggerFollowUpForSession(sessionId))
                                    .subscribe();
                            })
                            .doOnError(e -> {
                                log.error("Error during streaming", e);
                                String citationsJson = computeCitationsJson(contextResult, aiResponseBuilder.toString());
                                reactor.core.publisher.Mono.fromCallable(() -> messageRepository.save(Message.builder()
                                        .session(session)
                                        .role(MessageRole.ASSISTANT)
                                        .content(aiResponseBuilder.toString())
                                        .status(MessageStatus.ERROR)
                                        .citationsJson(citationsJson)
                                        .createdAt(LocalDateTime.now())
                                        .build()))
                                    .subscribeOn(Schedulers.boundedElastic())
                                    .subscribe();
                            })
                            .map(token -> ServerSentEvent.<String>builder(token)
                                    .event("message").build());

                    // Sequenced after tokenStream by Flux.concat, so by the time this runs,
                    // doOnComplete above has already populated finalCitationsJsonRef with the
                    // citations that match what the model actually wrote.
                    Flux<ServerSentEvent<String>> citationsStream = Flux.defer(() -> {
                        String citationsJson = finalCitationsJsonRef.get();
                        if (citationsJson != null) {
                            return Flux.just(ServerSentEvent.<String>builder(citationsJson)
                                    .event("citations").build());
                        }
                        return Flux.empty();
                    });

                    return Flux.concat(tokenStream, citationsStream);
                });
    }

    /**
     * Filters contextResult's retrieved documents down to only the ones the
     * model actually referenced via [CITE:n] in its final response text, then
     * serializes that subset to JSON. Returns null (not "[]") on a serialization
     * failure or when there's nothing to cite, matching the old behavior where
     * citationsJson being null means "render no citations UI" rather than
     * "render an empty citations list".
     */
    private String computeCitationsJson(ContextResult contextResult, String responseText) {
        List<Map<String, Object>> citations =
                citationService.extractCitedCitations(contextResult.documents(), responseText);
        if (citations.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(citations);
        } catch (Exception e) {
            log.error("Failed to serialize citations", e);
            return null;
        }
    }
}
