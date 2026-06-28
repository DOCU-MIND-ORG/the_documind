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

    /**
     * The original Attachment row that owns a given Cloudinary URL. Used when
     * a new upload's content hashes to something already in the corpus
     * (see AttachmentService#uploadFile's existingSourceUrl check) so that
     * upload can reuse this row via a new ViewAttachment link instead of
     * inserting a duplicate Attachment for content that's already on file.
     * Ordered ascending so a chain of dedup'd re-uploads always resolves back
     * to the first/original row, not whichever duplicate happened to be most
     * recent.
     */
    java.util.Optional<Attachment> findFirstByUrlOrderByUploadedAtAsc(String url);
}
