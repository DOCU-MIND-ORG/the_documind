package com.accenture.intern.docmind.dto.auth;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class UserUpdateDto {
    private String name;
    private String email;
    private String phoneNumber;
    private String profilePicture;
    private String gender;
    private String occupation;
    private String organization;
    private String jobTitle;
    private String education;
    private String interests;
    private String industry;
    private String bio;
}
