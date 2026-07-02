package com.accenture.intern.docmind.dto.attachment;

import com.accenture.intern.docmind.entity.AttachmentType;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class AttachmentResponse {
    private Long attachmentId;

    /**
     * The session this attachment was originally uploaded in (provenance).
     * May be null if that session was later deleted — the Attachment row
     * itself survives as part of DocMind's shared corpus, just detached from
     * any specific session.
     */
    private Long sessionId;

    /** The uploader's user id — lets the Explore page confirm row ownership client-side too. */
    private Long userId;

    private AttachmentType type;
    private String fileName;
    private String storagePath;
    private String url;
    private String mimeType;
    private Long fileSizeBytes;
    private LocalDateTime uploadedAt;
}
