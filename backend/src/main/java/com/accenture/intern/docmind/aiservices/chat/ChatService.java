package com.accenture.intern.docmind.aiservices.chat;

import com.accenture.intern.docmind.dto.chat.ChatJob;
import com.accenture.intern.docmind.dto.chat.ChatJobResponse;
import com.accenture.intern.docmind.dto.chat.ChatRequest;
import com.accenture.intern.docmind.entity.Message;
import com.accenture.intern.docmind.entity.MessageRole;
import com.accenture.intern.docmind.entity.MessageStatus;
import com.accenture.intern.docmind.entity.Session;
import com.accenture.intern.docmind.repository.MessageRepository;
import com.accenture.intern.docmind.repository.SessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.connection.stream.StringRecord;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.data.redis.connection.stream.*;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class ChatService {

    private final MessageRepository messageRepository;
    private final SessionRepository sessionRepository;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ReactiveRedisTemplate<String, Object> reactiveRedisTemplate;
    private final org.springframework.data.redis.core.ReactiveStringRedisTemplate reactiveStringRedisTemplate;
    private final ChatGenerationManager chatGenerationManager;

    public ChatService(MessageRepository messageRepository,
                       SessionRepository sessionRepository,
                       RedisTemplate<String, Object> redisTemplate,
                       ReactiveRedisTemplate<String, Object> reactiveRedisTemplate,
                       org.springframework.data.redis.core.ReactiveStringRedisTemplate reactiveStringRedisTemplate,
                       ChatGenerationManager chatGenerationManager) {
        this.messageRepository = messageRepository;
        this.sessionRepository = sessionRepository;
        this.redisTemplate = redisTemplate;
        this.reactiveRedisTemplate = reactiveRedisTemplate;
        this.reactiveStringRedisTemplate = reactiveStringRedisTemplate;
        this.chatGenerationManager = chatGenerationManager;
    }

    public ChatJobResponse submitMessage(Long sessionId, ChatRequest request) {
        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("Session not found"));

        long messageCount = messageRepository.countBySession(session);
        if (messageCount >= 200) {
            throw new RuntimeException("Session limit reached. A single session can only process up to 100 queries to maintain optimal context quality. Please start a new session.");
        }

        // Save User Message
        messageRepository.save(Message.builder()
                .session(session)
                .role(MessageRole.USER)
                .content(request.getMessage())
                .status(MessageStatus.COMPLETE)
                .createdAt(LocalDateTime.now())
                .build());

        // Save Assistant Placeholder
        Message assistantMessage = messageRepository.save(Message.builder()
                .session(session)
                .role(MessageRole.ASSISTANT)
                .content("")
                .status(MessageStatus.PROCESSING)
                .createdAt(LocalDateTime.now())
                .build());

        // Create Job Payload
        ChatJob job = ChatJob.builder()
                .messageId(assistantMessage.getMessageId())
                .sessionId(sessionId)
                .query(request.getMessage())
                .model(request.getModel())
                .timestamp(System.currentTimeMillis())
                .inflightJobIds(request.getInflightJobIds())
                .build();

        // Push to Redis Stream
        Map<String, Object> recordMap = new HashMap<>();
        recordMap.put("messageId", job.getMessageId().toString());
        recordMap.put("sessionId", job.getSessionId().toString());
        recordMap.put("query", job.getQuery());
        recordMap.put("model", job.getModel());
        recordMap.put("timestamp", job.getTimestamp().toString());
        if (job.getInflightJobIds() != null && !job.getInflightJobIds().isEmpty()) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                recordMap.put("inflightJobIds", mapper.writeValueAsString(job.getInflightJobIds()));
            } catch (Exception e) {
                log.error("Failed to serialize inflightJobIds", e);
            }
        }

        redisTemplate.opsForStream().add("chat_jobs", recordMap);
        log.info("Pushed job for message {} to chat_jobs", assistantMessage.getMessageId());

        return ChatJobResponse.builder()
                .messageId(assistantMessage.getMessageId())
                .status("PROCESSING")
                .build();
    }

    public java.util.Map<Object, Object> getActiveGeneration(Long sessionId) {
        String sessionActiveKey = "active-generation:" + sessionId;
        String messageId = (String) redisTemplate.opsForValue().get(sessionActiveKey);
        if (messageId == null) {
            return null;
        }
        String stateKey = "generation-state:" + messageId;
        return redisTemplate.opsForHash().entries(stateKey);
    }
    
    public void cancelGeneration(Long messageId) {
        String stateKey = "generation-state:" + messageId;
        redisTemplate.opsForHash().put(stateKey, "status", "CANCELLED");
        chatGenerationManager.cancel(messageId);
    }

    public Flux<ServerSentEvent<String>> streamChat(Long messageId, String lastEventId) {
        String streamKey = "chat-stream:" + messageId;

        var options = org.springframework.data.redis.stream.StreamReceiver.StreamReceiverOptions.builder()
                .pollTimeout(java.time.Duration.ofMillis(500)).build();
        
        var receiver = org.springframework.data.redis.stream.StreamReceiver.create(reactiveStringRedisTemplate.getConnectionFactory(), options);

        org.springframework.data.redis.connection.stream.StreamOffset<String> offset = org.springframework.data.redis.connection.stream.StreamOffset.create(streamKey, 
                (lastEventId != null && !lastEventId.isEmpty()) ? org.springframework.data.redis.connection.stream.ReadOffset.from(lastEventId) : org.springframework.data.redis.connection.stream.ReadOffset.from("0-0"));

        return receiver.receive(offset)
                .map(message -> {
                    try {
                        java.util.Map<?, ?> map = message.getValue();
                        String event = "message";
                        String data = "";
                        for (java.util.Map.Entry<?, ?> entry : map.entrySet()) {
                            String key = entry.getKey() instanceof byte[] ? new String((byte[]) entry.getKey()) : entry.getKey().toString();
                            String val = entry.getValue() instanceof byte[] ? new String((byte[]) entry.getValue()) : (entry.getValue() != null ? entry.getValue().toString() : "");
                            if ("event".equals(key)) event = val;
                            if ("data".equals(key)) data = val;
                        }
                        
                        return ServerSentEvent.<String>builder(data)
                                .id(message.getId().getValue())
                                .event(event)
                                .build();
                    } catch (Exception e) {
                        return ServerSentEvent.<String>builder(message.getValue().toString()).id(message.getId().getValue()).build();
                    }
                })
                .takeUntil(sse -> "done".equals(sse.event()));
    }
}
