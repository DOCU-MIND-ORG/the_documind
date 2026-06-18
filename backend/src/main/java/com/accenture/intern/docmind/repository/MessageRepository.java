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
}
