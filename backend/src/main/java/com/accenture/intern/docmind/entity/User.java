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

    @Column
    private String phoneNumber;

    @Column(columnDefinition = "TEXT")
    private String profilePicture;

    @Column
    private String gender;

    @Column
    private String occupation;

    @Column
    private String organization;

    @Column
    private String jobTitle;

    @Column
    private String education;

    @Column
    private String interests;

    @Column
    private String industry;

    @Column(columnDefinition = "TEXT")
    private String bio;
}
