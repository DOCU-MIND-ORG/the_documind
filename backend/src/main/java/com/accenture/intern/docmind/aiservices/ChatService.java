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

    public ChatService(ChatClient.Builder chatClientBuilder,
                       SessionRepository sessionRepository,
                       MessageRepository messageRepository,
                       ContextBuilderService contextBuilderService,
                       CitationService citationService,
                       ObjectMapper objectMapper,
                       ModelFactory modelFactory) {
        this.chatClient = chatClientBuilder.build();
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.contextBuilderService = contextBuilderService;
        this.citationService = citationService;
        this.objectMapper = objectMapper;
        this.modelFactory = modelFactory;
    }

    @Transactional
    public RagResponse chatWithCitations(String question, Long sessionId) {
        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("Session not found"));

        ContextResult contextResult = contextBuilderService.buildContext(question, sessionId).block();

        org.springframework.ai.google.genai.GoogleGenAiChatOptions options = modelFactory.getChatOptions(session.getUser(), null);
        String finalSystemPrompt = modelFactory.injectResponseStyle(session.getUser(), contextResult.systemPrompt());

        String answer = chatClient.prompt()
                .system(finalSystemPrompt)
                .user(contextResult.prompt())
                .options(options)
                .call()
                .content();

        List<Map<String, Object>> citations = citationService.extractCitations(contextResult.documents());
        boolean foundInDocuments = !contextResult.documents().isEmpty();

        String citationsJson = null;
        try {
            citationsJson = objectMapper.writeValueAsString(citations);
        } catch (Exception e) {
            log.error("Failed to serialize citations", e);
        }

        // Save User Message
        messageRepository.save(Message.builder()
                .session(session)
                .role(MessageRole.USER)
                .content(question)
                .status(MessageStatus.COMPLETE)
                .citationsJson(null)
                .createdAt(LocalDateTime.now())
                .build());

        // Save Bot Message
        messageRepository.save(Message.builder()
                .session(session)
                .role(MessageRole.ASSISTANT)
                .content(answer)
                .status(MessageStatus.COMPLETE)
                .citationsJson(citationsJson)
                .createdAt(LocalDateTime.now())
                .build());

        return new RagResponse(answer, citations, foundInDocuments);
    }

    public Flux<ServerSentEvent<String>> streamChat(Long sessionId, ChatRequest request) {

        reactor.core.publisher.Mono<Session> sessionMono = reactor.core.publisher.Mono.fromCallable(() ->
                sessionRepository.findById(sessionId)
                        .orElseThrow(() -> new RuntimeException("Session not found"))
        ).subscribeOn(Schedulers.boundedElastic());

        reactor.core.publisher.Mono<ContextResult> contextMono = contextBuilderService.buildContext(request.getMessage(), sessionId);

        return reactor.core.publisher.Mono.zip(sessionMono, contextMono)
                .flatMapMany(tuple -> {
                    Session session = tuple.getT1();
                    ContextResult contextResult = tuple.getT2();
                    List<Map<String, Object>> citations = citationService.extractCitations(contextResult.documents());
                    
                    String citationsJsonStr = null;
                    try {
                        citationsJsonStr = objectMapper.writeValueAsString(citations);
                    } catch (Exception e) {
                        log.error("Failed to serialize citations", e);
                    }
                    final String finalCitationsJson = citationsJsonStr;

                    // Save user message — fire and forget but handle errors
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

                    org.springframework.ai.google.genai.GoogleGenAiChatOptions options = modelFactory.getChatOptions(session.getUser(), request.getModel());
                    String finalSystemPrompt = modelFactory.injectResponseStyle(session.getUser(), contextResult.systemPrompt());

                    Flux<ServerSentEvent<String>> tokenStream = chatClient.prompt()
                            .system(finalSystemPrompt)
                            .user(contextResult.prompt())
                            .options(options)
                            .stream()
                            .content()
                            .doOnNext(token -> {
                                if (token != null) aiResponseBuilder.append(token);
                            })
                            .doOnComplete(() -> {
                                reactor.core.publisher.Mono.fromCallable(() -> messageRepository.save(Message.builder()
                                        .session(session)
                                        .role(MessageRole.ASSISTANT)
                                        .content(aiResponseBuilder.toString())
                                        .status(MessageStatus.COMPLETE)
                                        .citationsJson(finalCitationsJson)
                                        .createdAt(LocalDateTime.now())
                                        .build()))
                                    .subscribeOn(Schedulers.boundedElastic())
                                    .subscribe();
                            })
                            .doOnError(e -> {
                                log.error("Error during streaming", e);
                                reactor.core.publisher.Mono.fromCallable(() -> messageRepository.save(Message.builder()
                                        .session(session)
                                        .role(MessageRole.ASSISTANT)
                                        .content(aiResponseBuilder.toString())
                                        .status(MessageStatus.ERROR)
                                        .citationsJson(finalCitationsJson)
                                        .createdAt(LocalDateTime.now())
                                        .build()))
                                    .subscribeOn(Schedulers.boundedElastic())
                                    .subscribe();
                            })
                            .map(token -> ServerSentEvent.<String>builder(token)
                                    .event("message").build());

                    Flux<ServerSentEvent<String>> citationsStream = Flux.defer(() -> {
                        if (finalCitationsJson != null) {
                            return Flux.just(ServerSentEvent.<String>builder(finalCitationsJson)
                                    .event("citations").build());
                        }
                        return Flux.empty();
                    });

                    return saveUserMsg.thenMany(Flux.concat(tokenStream, citationsStream));
                });
    }
}
