package com.accenture.intern.docmind.dto.auth;

import com.accenture.intern.docmind.entity.User;
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
    private String profilePicture;
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

    public static UserDto fromEntity(User user) {
        if (user == null) return null;
        return UserDto.builder()
                .id(user.getId())
                .name(user.getName())
                .email(user.getEmail())
                .phoneNumber(user.getPhoneNumber())
                .profilePicture(user.getProfilePicture())
                .profileImageUrl(user.getProfileImageUrl())
                .gender(user.getGender())
                .occupation(user.getOccupation())
                .organization(user.getOrganization())
                .jobTitle(user.getJobTitle())
                .education(user.getEducation())
                .interests(user.getInterests())
                .industry(user.getIndustry())
                .bio(user.getBio())
                .build();
    }
}
