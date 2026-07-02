package com.accenture.intern.docmind.aiservices.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class MemoryGatingService {

    public MemoryGatingService() {
    }

    public boolean isMemorable(String userMessage, String assistantMessage) {
        try {
            // 1. Skip very short user messages ("ok", "yes", "cool", "hi", "thanks")
            if (userMessage.split("\\s+").length < 4) {
                return false;
            }
            
            // 2. Skip if the bot couldn't answer the question or had no context
            if (assistantMessage.contains("I couldn't find relevant information") ||
                assistantMessage.contains("I apologize") ||
                assistantMessage.contains("I currently have no information")) {
                return false;
            }
            
            // 3. Skip obvious bot QA and meta history questions via text matching
            String q = userMessage.toLowerCase();
            if (q.contains("what did i say") || q.contains("our chat") || 
                q.contains("what are your capabilities") || q.contains("who are you") ||
                q.contains("what can you do") || q.contains("what are you")) {
                return false;
            }
            
            log.info("Memory gating decision: true (User message: \"{}...\")", 
                    userMessage.length() > 30 ? userMessage.substring(0, 30) : userMessage);
            return true;
        } catch (Exception e) {
            log.warn("Memory gating evaluation failed, defaulting to false", e);
            return false;
        }
    }
}

