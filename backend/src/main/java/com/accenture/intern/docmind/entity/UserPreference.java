package com.accenture.intern.docmind.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "user_preferences")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserPreference {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Builder.Default
    @Column(nullable = false)
    private String theme = "light";

    @Builder.Default
    @Column(nullable = false)
    private String language = "en";

    @Builder.Default
    @Column(nullable = false)
    private Boolean citationEnabled = true;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ResponseStyle responseStyle = ResponseStyle.BEGINNER;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ModelName modelName = ModelName.GEMINI_3_1_FLASH_LITE;

    @Builder.Default
    @Column(nullable = false)
    private Double temperature = 0.7;
    
}
