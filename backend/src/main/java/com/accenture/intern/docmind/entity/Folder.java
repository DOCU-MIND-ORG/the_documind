package com.accenture.intern.docmind.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.*;

@Entity
@Table(name = "folders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Folder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String name;

    private String icon;

    @Column(name = "color_hex")
    private String colorHex;

    @Column(name = "display_order")
    private Integer displayOrder;

    @Column(nullable = false)
    @Builder.Default
    private Boolean pinned = false;

    @ManyToMany(mappedBy = "folders")
    @OrderBy("displayOrder ASC, createdAt DESC")
    @Builder.Default
    private Set<Session> sessions = new HashSet<>();

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
        if (pinned == null) pinned = false;
        if (displayOrder == null) displayOrder = 0;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
