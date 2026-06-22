package com.accenture.intern.docmind.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.*;


@Entity
@Table(name = "messages")
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

    @OneToMany(mappedBy = "message", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<Attachment> attachments = new ArrayList<>();
}
