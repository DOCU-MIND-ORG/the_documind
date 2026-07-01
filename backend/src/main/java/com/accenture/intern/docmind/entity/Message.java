package com.accenture.intern.docmind.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.*;


import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Entity
@Table(name = "messages", indexes = {
    @Index(name = "idx_message_session_id", columnList = "session_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long messageId;

    @ManyToOne
    @JoinColumn(name = "session_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Session session;

    @Enumerated(EnumType.STRING)
    private MessageRole role;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Enumerated(EnumType.STRING)
    private MessageStatus status;

    private LocalDateTime createdAt;

    @Column(columnDefinition = "TEXT")
    private String citationsJson;

    @Column(columnDefinition = "TEXT")
    private String visualsJson;

    // Removed: attachments collection — Attachment is now linked directly to
    // Session (not Message). The message_id column has been dropped from the
    // attachments table; see Attachment.java and the V2 migration script.

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
