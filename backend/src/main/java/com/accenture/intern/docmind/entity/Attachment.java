package com.accenture.intern.docmind.entity;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.*;

@Entity
@Table(name = "attachments")
@EntityListeners(AttachmentEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Attachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long attachmentId;

    @ManyToOne
    @JoinColumn(name = "message_id")
    private Message message;

    @Enumerated(EnumType.STRING)
    private AttachmentType type;

    /** Original filename as uploaded by the user */
    private String fileName;

    /** Relative path on disk, e.g. storage/pdfs/uuid_report.pdf - only set for attachment types still stored locally (TEXT, OTHER) */
    private String storagePath;

    /** Full public/internal URL - either our /files/{storagePath} (local types) or a Cloudinary secure_url (PDF, IMAGE) */
    private String url;

    /**
     * Cloudinary public_id for this asset, set whenever {@link #url} points at
     * Cloudinary (PDF or IMAGE attachments). Kept for reference/admin purposes
     * only - it is NOT used to delete the asset when this row is removed.
     * Both PDF and IMAGE attachments intentionally keep their Cloudinary asset
     * after deletion, since citations can keep referencing them (via sourceUrl /
     * imageUrl on the chunk) long after the attachment/session that uploaded
     * them is gone. See AttachmentEntityListener.
     */
    private String cloudinaryPublicId;

    /** "image" or "raw" - which Cloudinary resource_type cloudinaryPublicId was uploaded as */
    private String cloudinaryResourceType;

    private String mimeType;

    private Long fileSizeBytes;

    private LocalDateTime uploadedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;
}
