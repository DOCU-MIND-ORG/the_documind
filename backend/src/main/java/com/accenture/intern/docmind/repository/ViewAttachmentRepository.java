package com.accenture.intern.docmind.repository;

import com.accenture.intern.docmind.entity.ViewAttachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Tracks which attachments are associated with which session (the "View
 * Attachments" feature on a session's page) — kept separate from the
 * Attachment table itself so that deleting a session only removes its
 * membership rows here, never the underlying Attachment row. DocMind's
 * uploaded documents are a shared, persistent company knowledge base: once
 * uploaded, an attachment stays visible in Explore (and searchable) even
 * after every session that ever referenced it is deleted.
 */
public interface ViewAttachmentRepository extends JpaRepository<ViewAttachment, Long> {

    /**
     * All attachments currently associated with a session, most recently
     * added first — used by the per-session "View Attachments" panel.
     */
    @Query("SELECT va.attachment FROM ViewAttachment va WHERE va.session.sessionId = :sessionId ORDER BY va.addedAt DESC")
    List<com.accenture.intern.docmind.entity.Attachment> findAttachmentsBySessionId(@Param("sessionId") Long sessionId);

    /**
     * Deletes every membership row for a session. Called from
     * SessionService#deleteSession before the session itself is deleted —
     * this removes the session's "View Attachments" entries without
     * touching the Attachment rows (or their Cloudinary files), which must
     * survive independently as part of the shared corpus.
     */
    void deleteBySession_SessionId(Long sessionId);
}
