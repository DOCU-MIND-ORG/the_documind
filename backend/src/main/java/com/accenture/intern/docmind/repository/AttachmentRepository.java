package com.accenture.intern.docmind.repository;

import com.accenture.intern.docmind.entity.Attachment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Attachment rows are DocMind's shared, persistent company knowledge base —
 * once uploaded, a file stays here (and visible via the global Explore page,
 * see AttachmentService#getAllGlobalAttachments) regardless of which session
 * originally uploaded it or whether that session still exists.
 * <p>
 * Session-scoped "which attachments does this session currently reference"
 * lookups go through {@link ViewAttachmentRepository} instead, not this
 * repository — that's a separate, deliberately deletable join table.
 */
public interface AttachmentRepository extends JpaRepository<Attachment, Long> {

    /**
     * Attachments whose Attachment.session still points at the given session
     * (i.e. attachments originally uploaded there that haven't yet been
     * detached). Used by SessionService#deleteSession to null out that link
     * before the session itself is deleted, since Attachment.session is a
     * real FK column.
     */
    List<Attachment> findBySessionSessionId(Long sessionId);
}
