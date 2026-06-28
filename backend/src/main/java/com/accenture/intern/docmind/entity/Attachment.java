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

    // Removed message_id — attachments are now linked directly to a session
    @ManyToOne
    @JoinColumn(name = "session_id", nullable = true)
    private Session session;

    @Enumerated(EnumType.STRING)
    private AttachmentType type;

    /** Original filename as uploaded by the user */
    private String fileName;

    /** Relative path on disk, if any. Null if fully migrated to Cloudinary. */
    private String storagePath;

    /** Full public URL - a Cloudinary secure_url */
    private String url;

    /**
     * Cloudinary public_id for this asset.
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
