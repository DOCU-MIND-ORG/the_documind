package com.accenture.intern.docmind.aiservices.chat;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class ChatGenerationManager {

    public static class ChatSession {
        private final Thread workerThread;
        public final AtomicBoolean cancelled = new AtomicBoolean(false);

        public ChatSession(Thread workerThread) {
            this.workerThread = workerThread;
        }

        public Thread getWorkerThread() {
            return workerThread;
        }
    }

    private final ConcurrentHashMap<Long, ChatSession> activeSessions = new ConcurrentHashMap<>();

    public void register(Long messageId, ChatSession session) {
        activeSessions.put(messageId, session);
    }

    public void unregister(Long messageId) {
        activeSessions.remove(messageId);
    }

    public void cancel(Long messageId) {
        ChatSession session = activeSessions.remove(messageId);
        if (session == null) {
            return; // Already finished or doesn't exist
        }

        session.cancelled.set(true);
    }
}
