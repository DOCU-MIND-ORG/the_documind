package com.accenture.intern.docmind.repository;

import com.accenture.intern.docmind.entity.Session;
import com.accenture.intern.docmind.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Repository for Session entity.
 * Additional query methods to be added by the assigned developer.
 */
public interface SessionRepository extends JpaRepository<Session, Long> {

    List<Session> findByUser(User user);
}
