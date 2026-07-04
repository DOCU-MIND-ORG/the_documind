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

    public ChatService(MessageRepository messageRepository,
                       SessionRepository sessionRepository,
                       RedisTemplate<String, Object> redisTemplate,
                       ReactiveRedisTemplate<String, Object> reactiveRedisTemplate) {
        this.messageRepository = messageRepository;
        this.sessionRepository = sessionRepository;
        this.redisTemplate = redisTemplate;
        this.reactiveRedisTemplate = reactiveRedisTemplate;
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
                .build();

        // Push to Redis Stream
        Map<String, Object> recordMap = new HashMap<>();
        recordMap.put("messageId", job.getMessageId().toString());
        recordMap.put("sessionId", job.getSessionId().toString());
        recordMap.put("query", job.getQuery());
        recordMap.put("model", job.getModel());
        recordMap.put("timestamp", job.getTimestamp().toString());

        redisTemplate.opsForStream().add("chat_jobs", recordMap);
        log.info("Pushed job for message {} to chat_jobs", assistantMessage.getMessageId());

        return ChatJobResponse.builder()
                .messageId(assistantMessage.getMessageId())
                .status("PROCESSING")
                .build();
    }

    public Flux<ServerSentEvent<String>> streamChat(Long messageId) {
        String channelKey = "chat-tokens:" + messageId;
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

        return reactiveRedisTemplate.listenToChannel(channelKey)
                .map(message -> {
                    try {
                        String json = (String) message.getMessage();
                        java.util.Map<String, String> map = mapper.readValue(json, java.util.Map.class);
                        return map;
                    } catch (Exception e) {
                        return java.util.Map.of("event", "message", "data", message.getMessage().toString());
                    }
                })
                .takeUntil(map -> "done".equals(map.get("event")))
                .map(map -> {
                    String event = map.get("event");
                    String data = map.get("data");
                    return ServerSentEvent.<String>builder(data).event(event != null ? event : "message").build();
                });
    }
}
