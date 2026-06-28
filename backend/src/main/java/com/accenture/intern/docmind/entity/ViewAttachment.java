package com.accenture.intern.docmind.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * Tracks which attachments belong to a session for the "View Attachments"
 * feature on the website. Each row links a session to an attachment; when the
 * session is deleted, all its ViewAttachment rows are automatically removed
 * via CascadeType.ALL on Session.viewAttachments.
 *
 * Table: view_attachments
 */
@Entity
@Table(name = "view_attachments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ViewAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The session this view-attachment record belongs to. */
    @ManyToOne
    @JoinColumn(name = "session_id", nullable = false)
    private Session session;

    /** The actual attachment (PDF, image, URL, etc.). */
    @ManyToOne
    @JoinColumn(name = "attachment_id", nullable = false)
    private Attachment attachment;

    /** When this attachment was added to the session. */
    private LocalDateTime addedAt;
}
