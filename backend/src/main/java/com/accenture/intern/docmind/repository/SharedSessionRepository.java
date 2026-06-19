package com.accenture.intern.docmind.repository;

import com.accenture.intern.docmind.entity.SharedSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SharedSessionRepository extends JpaRepository<SharedSession, Long> {
    Optional<SharedSession> findByShareToken(String shareToken);
}
