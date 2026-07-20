package com.accenture.intern.docmind.repository;

import com.accenture.intern.docmind.entity.Attachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    @Modifying
    @Query("UPDATE Attachment a SET a.session = null WHERE a.session.sessionId = :sessionId")
    void detachAttachmentsFromSession(@Param("sessionId") Long sessionId);

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

    /**
     * Same as {@link #findFirstByUrlOrderByUploadedAtAsc}, but scoped to one
     * user. Dedup reuse only kicks in when THIS user has already uploaded this
     * exact content before — a different user uploading the same bytes gets
     * their own new Attachment row (see AttachmentService#uploadFile), so that
     * every user has their own deletable row in Explore even for shared
     * content, and {@link #findByUrl} can tell how many distinct users
     * currently reference a given Cloudinary asset.
     */
    java.util.Optional<Attachment> findFirstByUrlAndUserIdOrderByUploadedAtAsc(String url, Long userId);

    /**
     * Every Attachment row (across all users) that points at the same
     * Cloudinary/source URL. Used by AttachmentService#deleteExploreAttachment
     * to decide whether a file is "owned" by exactly one user (safe to hard-
     * delete from Cloudinary + document_chunks + Pinecone) or shared by
     * several users (in which case only the requesting user's own row is
     * removed).
     */
    List<Attachment> findByUrl(String url);

    /**
     * All attachments uploaded by one specific user, most recent first. Backs
     * the per-user Explore page (see ExploreController#getAllAttachments) —
     * each user only sees files they personally uploaded, not every file in
     * the shared corpus.
     */
    List<Attachment> findByUserIdOrderByUploadedAtDesc(Long userId);

    /**
     * Finds any attachments whose normalized title matches one of the provided n-grams exactly.
     */
    @Query("SELECT a FROM Attachment a WHERE a.normalizedTitle IN :ngrams")
    List<Attachment> findByNormalizedTitleIn(@Param("ngrams") List<String> ngrams);
}
