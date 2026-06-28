package com.accenture.intern.docmind.aiservices.chat;

import com.accenture.intern.docmind.aiservices.context.ContextBuilderService;
import com.accenture.intern.docmind.aiservices.context.CitationService;
import com.accenture.intern.docmind.aiservices.retrieval.MessagesPineconeVectorStore;
import com.accenture.intern.docmind.aiservices.memory.MemoryGatingService;
import com.accenture.intern.docmind.dto.chat.ChatJob;
import com.accenture.intern.docmind.dto.chat.ContextResult;
import com.accenture.intern.docmind.dto.chat.SessionUploadState;
import com.accenture.intern.docmind.dto.chat.UploadState;
import com.accenture.intern.docmind.dto.context.DocumentReference;
import com.accenture.intern.docmind.dto.context.SessionContext;
import com.accenture.intern.docmind.entity.Message;
import com.accenture.intern.docmind.entity.MessageStatus;
import com.accenture.intern.docmind.entity.Session;
import com.accenture.intern.docmind.aiservices.model.ModelFactory;
import com.accenture.intern.docmind.repository.MessageRepository;
import com.accenture.intern.docmind.repository.SessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.data.redis.connection.stream.*;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
public class LlmWorkerService {

    private final ChatClient chatClient;
    private final ContextBuilderService contextBuilderService;
    private final CitationService citationService;
    private final MessageRepository messageRepository;
    private final SessionRepository sessionRepository;
    private final ObjectMapper objectMapper;
    private final ModelFactory modelFactory;
    private final MessagesPineconeVectorStore messagesVectorStore;
    private final MemoryGatingService memoryGatingService;
    private final com.accenture.intern.docmind.service.SessionCacheService sessionCacheService;
    private final RedisTemplate<String, Object> redisTemplate;
    
    private StreamOperations<String, Object, Object> streamOps;
    private static final String CONSUMER_GROUP = "llm-workers";
    private static final String CONSUMER_NAME = "worker-1"; // Or dynamic UUID in prod
    private static final String STREAM_KEY = "chat_jobs";

    private final com.accenture.intern.docmind.aiservices.context.SuggestedQuestionsService suggestedQuestionsService;

    public LlmWorkerService(ChatClient.Builder chatClientBuilder,
                            ContextBuilderService contextBuilderService,
                            CitationService citationService,
                            MessageRepository messageRepository,
                            SessionRepository sessionRepository,
                            ObjectMapper objectMapper,
                            ModelFactory modelFactory,
                            MessagesPineconeVectorStore messagesVectorStore,
                            MemoryGatingService memoryGatingService,
                            com.accenture.intern.docmind.service.SessionCacheService sessionCacheService,
                            com.accenture.intern.docmind.aiservices.context.SuggestedQuestionsService suggestedQuestionsService,
                            RedisTemplate<String, Object> redisTemplate) {
        this.chatClient = chatClientBuilder.build();
        this.contextBuilderService = contextBuilderService;
        this.citationService = citationService;
        this.messageRepository = messageRepository;
        this.sessionRepository = sessionRepository;
        this.objectMapper = objectMapper;
        this.modelFactory = modelFactory;
        this.messagesVectorStore = messagesVectorStore;
        this.memoryGatingService = memoryGatingService;
        this.sessionCacheService = sessionCacheService;
        this.suggestedQuestionsService = suggestedQuestionsService;
        this.redisTemplate = redisTemplate;
    }

    @PostConstruct
    public void init() {
        streamOps = redisTemplate.opsForStream();
        try {
            streamOps.createGroup(STREAM_KEY, CONSUMER_GROUP);
        } catch (Exception e) {
            log.info("Consumer group likely exists already");
        }
    }

    @Scheduled(fixedDelay = 100)
    public void pollJobs() {
        try {
            List<MapRecord<String, Object, Object>> records = streamOps.read(
                    Consumer.from(CONSUMER_GROUP, CONSUMER_NAME),
                    StreamReadOptions.empty().count(1),
                    StreamOffset.create(STREAM_KEY,ReadOffset.lastConsumed())
            );

            if (records == null || records.isEmpty()) return;

            for (MapRecord<String, Object, Object> record : records) {
                processJob(record);
            }
        } catch (Exception e) {
            log.error("Error polling jobs from Redis stream", e);
        }
    }

    private void processJob(MapRecord<String, Object, Object> record) {
        Map<Object, Object> value = record.getValue();
        Long messageId = Long.parseLong((String) value.get("messageId"));
        Long sessionId = Long.parseLong((String) value.get("sessionId"));
        String query = (String) value.get("query");
        String model = (String) value.get("model");
        
        String lockKey = "lock:" + messageId;
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, "1", 5, TimeUnit.MINUTES);
        if (Boolean.FALSE.equals(acquired)) {
            log.info("Job {} already being processed", messageId);
            return;
        }

        try {
            executeLlmGeneration(messageId, sessionId, query, model);
            streamOps.acknowledge(STREAM_KEY, CONSUMER_GROUP, record.getId());
        } catch (Exception e) {
            log.error("Failed to process job {}", messageId, e);
            updateMessageStatus(messageId, MessageStatus.FAILED);
            publishToken(messageId, "error", "Generation failed: " + e.getMessage());
            publishToken(messageId, "done", ""); // End stream
        }
    }

    /**
     * The exact sentinel phrase the rag prompt instructs the model to return
     * verbatim when retrieval came back empty/irrelevant (see
     * prompts/ragprompt.st). We match on a stable, distinctive prefix rather
     * than the full sentence so minor wording drift in the prompt doesn't
     * silently break the retry path.
     */
    private static final String RETRIEVAL_FAILURE_SENTINEL = "I couldn't find relevant information in the uploaded documents";

    private void executeLlmGeneration(Long messageId, Long sessionId, String query, String model) {
        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("Session not found"));
        
        SessionUploadState state = sessionCacheService.getState(sessionId);
        boolean stillIndexing = state != null && (state.getState() == UploadState.EMBEDDING || state.getState() == UploadState.INGESTING);
        List<DocumentReference> uploadedDocs = state != null && state.getActiveDocumentNames() != null 
                ? state.getActiveDocumentNames().stream().map(name -> new DocumentReference(name, System.currentTimeMillis())).toList() 
                : List.of();
        String activeDoc = uploadedDocs.isEmpty() ? null : uploadedDocs.get(0).filename();
        SessionContext sessionContext = new SessionContext(sessionId, uploadedDocs, activeDoc, state != null ? state.getAliases() : Map.of());

        // We block here since we are in a worker thread.
        reactor.core.publisher.Sinks.Many<org.springframework.http.codec.ServerSentEvent<String>> progressSink = reactor.core.publisher.Sinks.many().unicast().onBackpressureBuffer();
        
        progressSink.asFlux().subscribe(sse -> {
            try {
                publishToken(messageId, sse.event(), sse.data());
            } catch (Exception ignored) {}
        });

        ContextResult contextResult = contextBuilderService.buildContext(query, sessionId, sessionContext, stillIndexing, progressSink).block();

        org.springframework.ai.google.genai.GoogleGenAiChatOptions options = modelFactory.getChatOptions(session.getUser(), model);
        String finalSystemPrompt = modelFactory.injectResponseStyle(session.getUser(), contextResult.systemPrompt());

        String firstPassText = streamAnswer(messageId, finalSystemPrompt, contextResult.prompt(), options);
        String finalText = firstPassText;

        // Adaptive RAG retry: a single-pass retrieval miss doesn't necessarily
        // mean the answer truly isn't in the corpus — it may just mean the
        // planner's chosen retrieval strategy didn't dig deep enough. Before
        // surfacing the "couldn't find" message to the user, retry once with
        // the adaptive multi-iteration search-and-refine loop (the same one
        // PlannerService reserves for queries it flags as hard) and only fall
        // back to the original failure message if that retry also comes up
        // empty.
        if (isRetrievalFailure(firstPassText)) {
            log.info("First-pass retrieval miss for message {}, retrying with adaptive RAG", messageId);

            // Tell the frontend to clear the failure text it just rendered —
            // it only ever appends streamed chunks, so without this reset the
            // retry's tokens would render glued onto the end of the discarded
            // first-pass answer instead of replacing it.
            publishToken(messageId, "retry", "");
            try {
                publishToken(messageId, "progress", new com.accenture.intern.docmind.dto.chat.ProgressEvent(
                        com.accenture.intern.docmind.dto.chat.ProgressStage.ADAPTIVE,
                        com.accenture.intern.docmind.dto.chat.ProgressStatus.RUNNING,
                        "Initial search came up empty, trying a deeper adaptive search...", null, null, null).toJson(objectMapper));
            } catch (Exception ignored) {}

            ContextResult adaptiveResult = contextBuilderService.buildContextAdaptive(query, sessionId, sessionContext).block();
            String adaptiveSystemPrompt = modelFactory.injectResponseStyle(session.getUser(), adaptiveResult.systemPrompt());

            String adaptiveText = streamAnswer(messageId, adaptiveSystemPrompt, adaptiveResult.prompt(), options);

            if (!isRetrievalFailure(adaptiveText)) {
                // The adaptive retry actually found something — commit to its
                // result (and its citations/top score) instead of the original miss.
                contextResult = adaptiveResult;
                finalText = adaptiveText;
            } else {
                // Adaptive retry also failed to find anything. We already told
                // the frontend to clear the screen for the retry attempt, so
                // re-publish the original failure text (instead of leaving the
                // message blank) before finalizing.
                publishToken(messageId, "message", firstPassText);
                finalText = firstPassText;
            }
        }

        List<Map<String, Object>> citations = citationService.extractCitations(contextResult.documents());
        String citationsJson = null;
        try {
            citationsJson = objectMapper.writeValueAsString(citations);
            publishToken(messageId, "citations", citationsJson);
        } catch (Exception ignored) {}

        String fullResponse = finalText;

        // Finalize
        Message msg = messageRepository.findById(messageId).orElseThrow();
        msg.setContent(fullResponse);
        msg.setStatus(MessageStatus.COMPLETED);
        msg.setCitationsJson(citationsJson);
        messageRepository.save(msg);

        publishToken(messageId, "done", "");
        
        // Trigger follow-up questions generation in the background
        suggestedQuestionsService.triggerFollowUpForSession(sessionId);
        
        // Clean up token stream after 5 minutes
        redisTemplate.expire("tokens:" + messageId, 5, TimeUnit.MINUTES);

        // Memory injection
        boolean isMemorable = memoryGatingService.isMemorable(query, fullResponse);
        Double topScore = contextResult.topScore() != null ? contextResult.topScore() : 0.0;
        if (isMemorable && topScore >= 0.4) {
            String pairContent = "User: " + query + "\nAssistant: " + fullResponse;
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("sessionId", sessionId);
            metadata.put("conversationPairId", messageId);
            metadata.put("timestamp", System.currentTimeMillis());
            messagesVectorStore.add(List.of(new org.springframework.ai.document.Document(pairContent, metadata)));
        }
    }

    private boolean isRetrievalFailure(String text) {
        return text != null && text.contains(RETRIEVAL_FAILURE_SENTINEL);
    }

    /**
     * Runs one LLM generation pass, streaming each token live to the client
     * over the message's token stream as it arrives, and returns the fully
     * accumulated text. Used for both the initial answer and (when needed)
     * the adaptive-RAG retry pass — see executeLlmGeneration, which emits a
     * "retry" reset event before calling this a second time so the frontend
     * clears the discarded first-pass text instead of appending onto it.
     */
    private String streamAnswer(Long messageId, String systemPrompt, String userPrompt, org.springframework.ai.google.genai.GoogleGenAiChatOptions options) {
        try {
            publishToken(messageId, "progress", new com.accenture.intern.docmind.dto.chat.ProgressEvent(
                    com.accenture.intern.docmind.dto.chat.ProgressStage.GENERATION,
                    com.accenture.intern.docmind.dto.chat.ProgressStatus.RUNNING,
                    "Generating final answer...", null, null, null).toJson(objectMapper));
        } catch (Exception ignored) {}

        StringBuilder fullResponse = new StringBuilder();

        chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .options(options)
                .stream()
                .content()
                .doOnNext(token -> {
                    if (token != null) {
                        fullResponse.append(token);
                        publishToken(messageId, "message", token);
                    }
                })
                .blockLast(); // Block until completion

        return fullResponse.toString();
    }

    private void publishToken(Long messageId, String event, String data) {
        Map<String, Object> map = new HashMap<>();
        map.put("event", event);
        map.put("data", data);
        streamOps.add("tokens:" + messageId, map);
    }
    
    private void updateMessageStatus(Long messageId, MessageStatus status) {
        messageRepository.findById(messageId).ifPresent(msg -> {
            msg.setStatus(status);
            messageRepository.save(msg);
        });
    }
}
