package com.accenture.intern.docmind.aiservices.fastintent;

import com.accenture.intern.docmind.aiservices.understanding.Intent;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class FastIntentService {

    private static final Set<String> FAST_PASS_QUERIES = Set.of(
        "hi", "hello", "hey", "thanks", "thank you", "ok", "okay", "clear history", "bye",
        "good morning", "good evening"
    );

    public Intent classifyIntent(String cleanQuery) {
        if (FAST_PASS_QUERIES.contains(cleanQuery) ||
            (cleanQuery.split("\\s+").length <= 3 &&
            (cleanQuery.startsWith("hi ") || cleanQuery.startsWith("hello ") || cleanQuery.startsWith("hey ")))) {
            return Intent.GREETING_ACK;
        }

        if (cleanQuery.startsWith("what did i upload") ||
            cleanQuery.contains("what files") ||
            cleanQuery.contains("what documents did i upload") ||
            cleanQuery.contains("how many files") ||
            cleanQuery.contains("how many uploaded files") ||
            cleanQuery.contains("my uploaded files")) {
            return Intent.SESSION_INFO;
        }

        if (cleanQuery.startsWith("what did i") ||
            cleanQuery.startsWith("what have we") ||
            cleanQuery.contains("what did i ask") ||
            (cleanQuery.contains("earlier") && cleanQuery.contains("said"))) {
            return Intent.META_HISTORY;
        }

        if (cleanQuery.contains("what are your") ||
            cleanQuery.contains("who are you") ||
            cleanQuery.contains("what can you do") ||
            cleanQuery.contains("what are you")) {
            return Intent.BOT_QA;
        }

        return null;
    }
}
