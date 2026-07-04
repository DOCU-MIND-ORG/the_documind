package com.accenture.intern.docmind.aiservices.memory;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class MemoryWorkerTriggerService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
    
    private static final String STREAM_KEY = "memory_jobs";

    public MemoryWorkerTriggerService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void triggerMemoryJob(Long sessionId, List<String> oldMessages) {
        try {
            com.accenture.intern.docmind.dto.job.MemoryJobPayload payload = new com.accenture.intern.docmind.dto.job.MemoryJobPayload();
            payload.setSessionId(sessionId);
            payload.setEpisodeContent(String.join("\n", oldMessages));
            
            Map<String, Object> recordMap = new HashMap<>();
            recordMap.put("payload", objectMapper.writeValueAsString(payload));

            redisTemplate.opsForStream().add(STREAM_KEY, recordMap);
            log.info("Pushed closed episode to {} for Session {}", STREAM_KEY, sessionId);
        } catch (Exception e) {
            log.error("Failed to push memory job to stream for Session {}", sessionId, e);
        }
    }
}
