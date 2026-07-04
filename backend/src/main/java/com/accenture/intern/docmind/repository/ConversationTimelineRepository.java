package com.accenture.intern.docmind.repository;

import com.accenture.intern.docmind.entity.ConversationTimeline;
import com.accenture.intern.docmind.entity.Session;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ConversationTimelineRepository extends JpaRepository<ConversationTimeline, Long> {
    List<ConversationTimeline> findBySessionOrderByStartTurnAsc(Session session);
    List<ConversationTimeline> findBySessionOrderByCreatedAtAsc(Session session);
}
