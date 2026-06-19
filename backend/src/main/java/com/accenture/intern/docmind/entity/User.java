package com.accenture.intern.docmind.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String password;    

    @Column(nullable = false)
    private Boolean isActive;

    @Column(name = "profile_image_url")
    private String profileImageUrl;

    @Column(name = "profile_image_public_id")
    private String profileImagePublicId;
}
