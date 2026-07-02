package com.accenture.intern.docmind.dto.attachment;

import lombok.Builder;
import lombok.Data;

/**
 * Returned from DELETE /api/explore/attachments/{attachmentId} so the
 * frontend can show an accurate message: whether the file was fully purged
 * (Cloudinary + document_chunks + Pinecone, because this user was the only
 * one who had it) or just detached from this user's own Explore list
 * (because other users still reference the same content).
 */
@Data
@Builder
public class AttachmentDeleteResponse {
    private Long attachmentId;

    /** True if this was the only user referencing the file, so everything was purged. */
    private boolean fullyDeleted;

    /** How many distinct attachment rows (i.e. users) referenced this file before this delete. */
    private int ownerCount;

    private String message;
}
