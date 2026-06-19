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
    
    @Column(nullable = true)
    private String phoneNumber;

    @Column(nullable = true)
    private String gender;

    @Column(nullable = true)
    private String occupation;

    @Column(nullable = true)
    private String organization;

    @Column(nullable = true)
    private String jobTitle;

    @Column(nullable = true)
    private String education;

    @Column(columnDefinition = "TEXT")
    private String interests;

    @Column(nullable = true)
    private String industry;

    @Column(columnDefinition = "TEXT")
    private String bio;

    @Column(name = "profile_image_url")
    private String profileImageUrl;

    @Column(name = "profile_image_public_id")
    private String profileImagePublicId;
}
