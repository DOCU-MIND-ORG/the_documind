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
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
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
    private final com.accenture.intern.docmind.aiservices.memory.TopicShiftDetectorService topicShiftDetectorService;
    private final com.accenture.intern.docmind.service.SessionCacheService sessionCacheService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;
    
    private StreamOperations<String, Object, Object> streamOps;
    private static final String CONSUMER_GROUP = "llm-workers";
    private final String consumerName = "llm-worker-1";
    private static final String STREAM_KEY = "chat_jobs";
    private volatile boolean running = true;
    private final java.util.concurrent.ExecutorService workerExecutor;
    private final com.accenture.intern.docmind.service.AnalyticsService analyticsService;
    private final TransactionTemplate transactionTemplate;

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
                            com.accenture.intern.docmind.aiservices.memory.TopicShiftDetectorService topicShiftDetectorService,
                            com.accenture.intern.docmind.service.SessionCacheService sessionCacheService,
                            com.accenture.intern.docmind.aiservices.context.SuggestedQuestionsService suggestedQuestionsService,
                            RedisTemplate<String, Object> redisTemplate,
                            org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate,
                            java.util.concurrent.ExecutorService workerExecutor,
                            com.accenture.intern.docmind.service.AnalyticsService analyticsService,
                            TransactionTemplate transactionTemplate) {
        this.chatClient = chatClientBuilder.build();
        this.contextBuilderService = contextBuilderService;
        this.citationService = citationService;
        this.messageRepository = messageRepository;
        this.sessionRepository = sessionRepository;
        this.objectMapper = objectMapper;
        this.modelFactory = modelFactory;
        this.messagesVectorStore = messagesVectorStore;
        this.memoryGatingService = memoryGatingService;
        this.topicShiftDetectorService = topicShiftDetectorService;
        this.sessionCacheService = sessionCacheService;
        this.suggestedQuestionsService = suggestedQuestionsService;
        this.redisTemplate = redisTemplate;
        this.stringRedisTemplate = stringRedisTemplate;
        this.workerExecutor = workerExecutor;
        this.analyticsService = analyticsService;
        this.transactionTemplate = transactionTemplate;
    }

    @PostConstruct
    public void init() {
        streamOps = redisTemplate.opsForStream();
        try {
            streamOps.createGroup(STREAM_KEY, ReadOffset.from("0"), CONSUMER_GROUP);
        } catch (Exception e) {
            log.info("Consumer group likely exists already");
        }
        workerExecutor.submit(this::runWorker);
    }

    @jakarta.annotation.PreDestroy
    public void destroy() {
        running = false;
    }

    public void runWorker() {
        log.info("LlmWorkerService started polling for jobs...");
        while (running) {
            try {
                List<MapRecord<String, Object, Object>> records = streamOps.read(
                        Consumer.from(CONSUMER_GROUP, consumerName),
                        StreamReadOptions.empty().block(Duration.ofSeconds(30)).count(1),
                        StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed())
                );

                if (records != null && !records.isEmpty()) {
                    for (MapRecord<String, Object, Object> record : records) {
                        processJob(record);
                    }
                } else {
                    // Try PEL (Pending Entries List) if no new messages
                    records = streamOps.read(
                            Consumer.from(CONSUMER_GROUP, consumerName),
                            StreamReadOptions.empty().count(10),
                            StreamOffset.create(STREAM_KEY, ReadOffset.from("0"))
                    );
                    if (records != null && !records.isEmpty()) {
                        for (MapRecord<String, Object, Object> record : records) {
                            log.info("Recovered job from PEL: {}", record.getId());
                            processJob(record);
                        }
                    }
                }
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("NOGROUP")) {
                    try {
                        streamOps.createGroup(STREAM_KEY, ReadOffset.from("0"), CONSUMER_GROUP);
                    } catch (Exception ignored) {
                    }
                    try {
                        Thread.sleep(1000); // Backoff slightly to avoid tight loop
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                } else {
                    log.error("Error polling jobs from Redis stream", e);
                    try {
                        Thread.sleep(5000); // Backoff on error
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
    }

    private void processJob(MapRecord<String, Object, Object> record) {
        Map<Object, Object> value = record.getValue();
        Long messageId = Long.parseLong((String) value.get("messageId"));
        log.info("LlmWorkerService picked up job for messageId: {}", messageId);
        
        Long sessionId = Long.parseLong((String) value.get("sessionId"));
        String query = (String) value.get("query");
        String model = (String) value.get("model");
        
        String lockKey = "lock:" + messageId;
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, "1", 5, TimeUnit.MINUTES);
        if (Boolean.FALSE.equals(acquired)) {
            log.info("Job {} already being processed", messageId);
            return;
        }

        // Set active generation state
        String stateKey = "generation-state:" + messageId;
        Map<String, String> stateMap = new HashMap<>();
        stateMap.put("sessionId", sessionId.toString());
        stateMap.put("messageId", messageId.toString());
        stateMap.put("status", "RUNNING");
        stateMap.put("startedAt", String.valueOf(System.currentTimeMillis()));
        redisTemplate.opsForHash().putAll(stateKey, stateMap);
        redisTemplate.expire(stateKey, 30, TimeUnit.MINUTES);

        // Point the session to the active generation
        String sessionActiveKey = "active-generation:" + sessionId;
        redisTemplate.opsForValue().set(sessionActiveKey, messageId.toString(), 30, TimeUnit.MINUTES);

        try {
            transactionTemplate.executeWithoutResult(status -> {
                long startTime = System.currentTimeMillis();
                executeLlmGeneration(messageId, sessionId, query, model);
                long duration = System.currentTimeMillis() - startTime;
                
                analyticsService.incrementChatRequests();
                Message msg = messageRepository.findById(messageId).orElse(null);
                long tokens = (msg != null && msg.getContent() != null) ? msg.getContent().length() / 4 : 0;
                analyticsService.recordLlmGeneration(tokens, duration);
            });
            
            redisTemplate.opsForHash().put(stateKey, "status", "COMPLETED");
            redisTemplate.delete(sessionActiveKey);
        } catch (Exception e) {
            log.error("Failed to process job {}", messageId, e);
            updateMessageStatus(messageId, MessageStatus.FAILED);
            publishToken(messageId, "error", "Generation failed: " + e.getMessage());
            publishToken(messageId, "done", ""); // End stream
            
            redisTemplate.opsForHash().put(stateKey, "status", "FAILED");
            redisTemplate.delete(sessionActiveKey);
        } finally {
            streamOps.acknowledge(STREAM_KEY, CONSUMER_GROUP, record.getId());
        }
    }

    private void executeLlmGeneration(Long messageId, Long sessionId, String query, String model) {
        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("Session not found"));
        
        // Topic Shift Detection (Memory Architecture Layer 3/4)
        boolean topicShifted = topicShiftDetectorService.detectTopicShift(sessionId, query);
        
        SessionUploadState state = sessionCacheService.getState(sessionId);
        
        int waitSeconds = 0;
        while (state != null && (state.getState() == UploadState.EMBEDDING || state.getState() == UploadState.INGESTING) && waitSeconds < 120) {
            log.info("Session {} is currently {}, waiting for ingestion to complete before generating response...", sessionId, state.getState());
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            state = sessionCacheService.getState(sessionId);
            waitSeconds++;
        }
        
        boolean stillIndexing = state != null && (state.getState() == UploadState.EMBEDDING || state.getState() == UploadState.INGESTING);
        List<DocumentReference> uploadedDocs;
        if (state != null && state.getActiveDocumentNames() != null) {
            uploadedDocs = state.getActiveDocumentNames().stream()
                    .map(name -> new DocumentReference(name, System.currentTimeMillis())).toList();
        } else {
            uploadedDocs = session.getAttachments().stream()
                    .filter(a -> a.getType() != com.accenture.intern.docmind.entity.AttachmentType.IMAGE)
                    .map(a -> new DocumentReference(a.getFileName(), System.currentTimeMillis())).toList();
        }
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

        String fullResponse = streamAnswer(messageId, finalSystemPrompt, contextResult.prompt(), options);
        progressSink.tryEmitComplete();


        List<Map<String, Object>> citations = citationService.extractCitations(contextResult.documents());
        String citationsJson = null;
        try {
            citationsJson = objectMapper.writeValueAsString(citations);
            publishToken(messageId, "citations", citationsJson);
        } catch (Exception ignored) {}

        String visualsJson = null;
        if (contextResult.visuals() != null && !contextResult.visuals().isEmpty()) {
            try {
                visualsJson = objectMapper.writeValueAsString(contextResult.visuals());
                publishToken(messageId, "visuals", visualsJson);
            } catch (Exception ignored) {}
        }

        // Finalize
        Message msg = messageRepository.findById(messageId).orElseThrow();
        msg.setContent(fullResponse);
        msg.setStatus(MessageStatus.COMPLETED);
        msg.setCitationsJson(citationsJson);
        msg.setVisualsJson(visualsJson);
        messageRepository.save(msg);

        publishToken(messageId, "done", "");
        
        // Trigger follow-up questions generation in the background
        suggestedQuestionsService.triggerFollowUpForSession(sessionId);

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
            
            // Add to current episode for topic shift detection
            topicShiftDetectorService.appendAssistantResponse(sessionId, fullResponse);
        }
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
        Map<String, String> map = new HashMap<>();
        map.put("event", event);
        map.put("data", data != null ? data : "");
        try {
            String streamKey = "chat-stream:" + messageId;
            stringRedisTemplate.opsForStream().add(streamKey, map);
            stringRedisTemplate.expire(streamKey, 30, TimeUnit.MINUTES);
        } catch(Exception e) {
            log.error("Failed to publish token to stream", e);
        }
    }
    
    private void updateMessageStatus(Long messageId, MessageStatus status) {
        messageRepository.findById(messageId).ifPresent(msg -> {
            msg.setStatus(status);
            messageRepository.save(msg);
        });
    }
}
