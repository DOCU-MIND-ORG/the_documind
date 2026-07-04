package com.accenture.intern.docmind.aiservices.memory;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;
import java.util.List;
import java.util.ArrayList;

@Slf4j
@Service
public class TopicShiftDetectorService {

    private final ChatClient chatClient;
    private final RedisTemplate<String, Object> redisTemplate;
    private final MemoryWorkerTriggerService memoryWorkerTriggerService;
    
    private static final String MESSAGES_KEY_PREFIX = "session:episode:messages:";

    public TopicShiftDetectorService(ChatClient.Builder chatClientBuilder, RedisTemplate<String, Object> redisTemplate, MemoryWorkerTriggerService memoryWorkerTriggerService) {
        this.chatClient = chatClientBuilder.build();
        this.redisTemplate = redisTemplate;
        this.memoryWorkerTriggerService = memoryWorkerTriggerService;
    }

    public boolean detectTopicShift(Long sessionId, String userQuery) {
        String messagesKey = MESSAGES_KEY_PREFIX + sessionId;
        
        List<String> currentEpisodeMessages = (List<String>) redisTemplate.opsForValue().get(messagesKey);
        
        if (currentEpisodeMessages == null || currentEpisodeMessages.isEmpty()) {
            // First message in the session/episode
            updateCurrentEpisode(sessionId, userQuery, null);
            return false; 
        }

        String prompt = "You are a conversational memory router. Based on the current conversation episode below, determine if the user's new query represents a SIGNIFICANT topic shift.\n\n"
            + "Current Episode:\n"
            + String.join("\n", currentEpisodeMessages) + "\n\n"
            + "New Query: " + userQuery + "\n\n"
            + "Respond with EXACTLY 'TRUE' if the topic has shifted to a completely different subject, or 'FALSE' if it is a follow-up, related, or continuing the same broad topic.";
            
        String response = "";
        try {
            response = chatClient.prompt(prompt).call().content();
        } catch (Exception e) {
            log.warn("[TopicShiftDetector] LLM failed to evaluate topic shift, assuming FALSE.", e);
        }

        boolean shifted = response != null && response.trim().toUpperCase().contains("TRUE");
        log.info("[TopicShiftDetector] Session {} - Query: \"{}\" | Topic Shifted: {}", sessionId, userQuery, shifted);

        if (shifted) {
            // Push old episode to memory_jobs
            if (currentEpisodeMessages != null && !currentEpisodeMessages.isEmpty()) {
                memoryWorkerTriggerService.triggerMemoryJob(sessionId, currentEpisodeMessages);
            }
            
            // Start new episode with current query
            updateCurrentEpisode(sessionId, userQuery, null);
        } else {
            // Append to current episode
            List<String> messages = (List<String>) redisTemplate.opsForValue().get(messagesKey);
            if (messages == null) messages = new ArrayList<>();
            messages.add("User: " + userQuery);
            redisTemplate.opsForValue().set(messagesKey, messages);
        }

        return shifted;
    }
    
    public void appendAssistantResponse(Long sessionId, String assistantResponse) {
        String messagesKey = MESSAGES_KEY_PREFIX + sessionId;
        List<String> messages = (List<String>) redisTemplate.opsForValue().get(messagesKey);
        if (messages != null) {
            messages.add("Assistant: " + assistantResponse);
            redisTemplate.opsForValue().set(messagesKey, messages);
        }
    }

    private void updateCurrentEpisode(Long sessionId, String firstMessage, String assistantResponse) {
        List<String> initialMessages = new ArrayList<>();
        initialMessages.add("User: " + firstMessage);
        if (assistantResponse != null) {
            initialMessages.add("Assistant: " + assistantResponse);
        }
        redisTemplate.opsForValue().set(MESSAGES_KEY_PREFIX + sessionId, initialMessages);
    }
}
