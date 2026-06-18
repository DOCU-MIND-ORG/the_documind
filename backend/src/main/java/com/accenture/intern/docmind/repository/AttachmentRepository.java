package com.accenture.intern.docmind.repository;

import com.accenture.intern.docmind.entity.Attachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AttachmentRepository extends JpaRepository<Attachment, Long> {

    /**
     * Fetch all attachments for a given session by walking
     * Attachment → Message → Session.
     */
    @Query("SELECT a FROM Attachment a WHERE a.message.session.sessionId = :sessionId ORDER BY a.uploadedAt DESC")
    List<Attachment> findBySessionId(@Param("sessionId") Long sessionId);
}
