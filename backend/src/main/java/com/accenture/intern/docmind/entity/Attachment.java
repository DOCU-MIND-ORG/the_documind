package com.accenture.intern.docmind.entity;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.*;

@Entity
@Table(name = "attachments")
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

    /** Relative path on disk, e.g. storage/pdfs/uuid_report.pdf */
    private String storagePath;

    /** Full public/internal URL (can be generated later for serving) */
    private String url;

    private String mimeType;

    private Long fileSizeBytes;

    private LocalDateTime uploadedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;
}
