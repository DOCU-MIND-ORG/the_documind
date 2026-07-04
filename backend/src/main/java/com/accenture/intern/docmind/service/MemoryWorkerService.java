package com.accenture.intern.docmind.service;

import com.accenture.intern.docmind.aiservices.retrieval.IntegratedPineconeVectorStore;
import com.accenture.intern.docmind.dto.context.SemanticChunk;
import com.accenture.intern.docmind.dto.job.EpisodicSummaryResult;
import com.accenture.intern.docmind.dto.job.MemoryJobPayload;
import com.accenture.intern.docmind.entity.ConversationTimeline;
import com.accenture.intern.docmind.entity.Message;
import com.accenture.intern.docmind.entity.Session;
import com.accenture.intern.docmind.repository.ConversationTimelineRepository;
import com.accenture.intern.docmind.repository.MessageRepository;
import com.accenture.intern.docmind.repository.SessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class MemoryWorkerService {

    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;
    private final ConversationTimelineRepository timelineRepository;
    private final IntegratedPineconeVectorStore pineconeVectorStore;
    private final ChatClient chatClient;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;
    private final ExecutorService workerExecutor;

    private StreamOperations<String, Object, Object> streamOps;
    private static final String CONSUMER_GROUP = "memory-workers";
    private final String consumerName = "worker-" + UUID.randomUUID().toString();
    private static final String STREAM_KEY = "memory_jobs";
    private volatile boolean running = true;

    public MemoryWorkerService(SessionRepository sessionRepository,
                               MessageRepository messageRepository,
                               ConversationTimelineRepository timelineRepository,
                               IntegratedPineconeVectorStore pineconeVectorStore,
                               ChatClient.Builder chatClientBuilder,
                               RedisTemplate<String, Object> redisTemplate,
                               ObjectMapper objectMapper,
                               ExecutorService workerExecutor) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.timelineRepository = timelineRepository;
        this.pineconeVectorStore = pineconeVectorStore;
        this.chatClient = chatClientBuilder.build();
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.workerExecutor = workerExecutor;
    }

    @PostConstruct
    public void init() {
        streamOps = redisTemplate.opsForStream();
        try {
            streamOps.createGroup(STREAM_KEY, CONSUMER_GROUP);
        } catch (Exception e) {
            log.info("Memory consumer group likely exists already");
        }
        workerExecutor.submit(this::runWorker);
    }

    @PreDestroy
    public void destroy() {
        running = false;
    }

    public void runWorker() {
        while (running) {
            try {
                List<MapRecord<String, Object, Object>> records = streamOps.read(
                        Consumer.from(CONSUMER_GROUP, consumerName),
                        org.springframework.data.redis.connection.stream.StreamReadOptions.empty().block(Duration.ofSeconds(30)).count(1),
                        StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed())
                );

                if (records != null && !records.isEmpty()) {
                    for (MapRecord<String, Object, Object> record : records) {
                        processJob(record);
                    }
                } else {
                    records = streamOps.read(
                            Consumer.from(CONSUMER_GROUP, consumerName),
                            org.springframework.data.redis.connection.stream.StreamReadOptions.empty().count(10),
                            StreamOffset.create(STREAM_KEY, ReadOffset.from("0"))
                    );
                    if (records != null) {
                        for (MapRecord<String, Object, Object> record : records) {
                            processJob(record);
                        }
                    }
                }
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("NOGROUP")) {
                    try {
                        streamOps.createGroup(STREAM_KEY, CONSUMER_GROUP);
                    } catch (Exception ignored) {}
                } else {
                    log.error("Error polling memory jobs from Redis stream", e);
                }
            }
        }
    }

    private void processJob(MapRecord<String, Object, Object> record) {
        Map<Object, Object> value = record.getValue();
        Object payloadObj = value.get("payload");
        if (payloadObj == null) {
            streamOps.acknowledge(STREAM_KEY, CONSUMER_GROUP, record.getId());
            return;
        }

        MemoryJobPayload payload;
        try {
            if (payloadObj instanceof String) {
                payload = objectMapper.readValue((String) payloadObj, MemoryJobPayload.class);
            } else {
                payload = objectMapper.convertValue(payloadObj, MemoryJobPayload.class);
            }
        } catch (Exception e) {
            log.error("Failed to parse memory payload", e);
            streamOps.acknowledge(STREAM_KEY, CONSUMER_GROUP, record.getId());
            return;
        }

        String lockKey = "memory_lock:" + payload.getSessionId() + ":" + payload.getStartTurn();
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, "1", 5, TimeUnit.MINUTES);
        if (Boolean.FALSE.equals(acquired)) {
            return;
        }

        try {
            executeSummarization(payload);
        } catch (Exception e) {
            log.error("Failed to process memory job", e);
        } finally {
            streamOps.acknowledge(STREAM_KEY, CONSUMER_GROUP, record.getId());
        }
    }

    private void executeSummarization(MemoryJobPayload payload) {
        Session session = sessionRepository.findById(payload.getSessionId()).orElseThrow();

        // 1. Ask LLM to extract episodic summary
        String prompt = "Summarize the following conversation episode.\n" +
                "Extract the main topic, a concise summary, key entities, and assign an importance score (0.0 to 1.0).\n\n" +
                payload.getEpisodeContent();

        EpisodicSummaryResult result = chatClient.prompt(prompt)
                .call()
                .entity(EpisodicSummaryResult.class);

        if (result == null) return;

        // 2. Save to Postgres (ConversationTimeline - Layer 3)
        ConversationTimeline timeline = ConversationTimeline.builder()
                .session(session)
                .startTurn(payload.getStartTurn())
                .endTurn(payload.getEndTurn())
                .topic(result.getTopic())
                .summary(result.getSummary())
                .entities(result.getEntities())
                .keywords(result.getKeywords())
                .importanceScore(result.getImportanceScore())
                .createdAt(LocalDateTime.now())
                .build();
        timelineRepository.save(timeline);

        // 3. Save to Pinecone (Episodic Memory - Layer 4)
        Document doc = new Document(
                "Episode: " + result.getTopic() + "\n\n" + result.getSummary(),
                Map.of(
                        "type", "EPISODE_SUMMARY",
                        "sessionId", payload.getSessionId(),
                        "topic", result.getTopic()
                )
        );
        pineconeVectorStore.add(List.of(doc));
        
        log.info("Successfully created Episode Summary for Session {} (Turns {}-{})", payload.getSessionId(), payload.getStartTurn(), payload.getEndTurn());
    }
}
