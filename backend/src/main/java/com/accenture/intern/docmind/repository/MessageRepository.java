package com.accenture.intern.docmind.repository;

import com.accenture.intern.docmind.entity.Message;
import com.accenture.intern.docmind.entity.Session;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Repository for Message entity.
 * Additional query methods to be added by the assigned developer.
 */
public interface MessageRepository extends JpaRepository<Message, Long> {

    List<Message> findBySession(Session session);

    List<Message> findBySessionOrderByCreatedAtAsc(Session session);

    /**
     * Most recent messages for a session, newest first. Used to build short-term
     * conversation history (e.g. for prompt context and follow-up-question
     * generation) without needing to load the full Session entity first.
     * Callers should reverse the result to get chronological (oldest-first) order.
     */
    List<Message> findTop10BySession_SessionIdOrderByCreatedAtDesc(Long sessionId);
}
