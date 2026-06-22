package com.accenture.intern.docmind.dto.auth;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class UserDto {
    private Long id;
    private String name;
    private String email;
    private String phoneNumber;
    private String profileImageUrl;
    private String gender;
    private String occupation;
    private String organization;
    private String jobTitle;
    private String education;
    private String interests;
    private String industry;
    private String bio;
    private List<String> roles;
}
