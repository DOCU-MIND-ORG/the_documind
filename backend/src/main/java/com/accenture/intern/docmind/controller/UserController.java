package com.accenture.intern.docmind.controller;

import com.accenture.intern.docmind.dto.user.ProfileImageUpdateDto;
import com.accenture.intern.docmind.entity.User;
import com.accenture.intern.docmind.repository.UserRepository;
import com.accenture.intern.docmind.service.CloudinaryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserRepository userRepository;
    private final CloudinaryService cloudinaryService;

    @PutMapping("/{id}/profile-image")
    public ResponseEntity<?> updateProfileImage(@PathVariable Long id, @RequestBody ProfileImageUpdateDto dto) {
        Optional<User> optionalUser = userRepository.findById(id);
        
        if (optionalUser.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        User user = optionalUser.get();

        // Check if there's an existing profile image public ID
        if (user.getProfileImagePublicId() != null && !user.getProfileImagePublicId().isEmpty()) {
            // Delete the old image from Cloudinary
            cloudinaryService.deleteImage(user.getProfileImagePublicId());
        }

        // Update the user with the new image info
        user.setProfileImageUrl(dto.getLink());
        user.setProfileImagePublicId(dto.getPublic_id());
        userRepository.save(user);

        return ResponseEntity.ok("Profile image updated successfully");
    }
}
