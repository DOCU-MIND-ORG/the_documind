package com.accenture.intern.docmind.service;

import com.accenture.intern.docmind.dto.AuthResponse;
import com.accenture.intern.docmind.dto.LoginRequest;
import com.accenture.intern.docmind.dto.SignupRequest;
import com.accenture.intern.docmind.dto.UserDto;
import com.accenture.intern.docmind.entities.*;
import com.accenture.intern.docmind.repository.UserRepository;
import com.accenture.intern.docmind.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;

    private final BCryptPasswordEncoder passwordEncoder =
            new BCryptPasswordEncoder();

    public AuthResponse SignUp(SignupRequest request) {

        if (userRepository.existsByEmail(request.getEmail())) {
            throw new RuntimeException("Email already exists");
        }

        User user = User.builder()
                .name(request.getName())
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))               
                .isActive(true)
                .build();
        
        user = userRepository.save(user);

        return generateAuthResponse(user, "User Registered Successfully");
    }

    public AuthResponse Login(LoginRequest request) {

        String email = request.getEmail();
        User user = userRepository.findByEmail(email);

        if (user == null) {
            throw new RuntimeException("User not found");
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new RuntimeException("Invalid credentials");
        }

        return generateAuthResponse(user, "Successfully logged in .");
    }

    private AuthResponse generateAuthResponse(User user, String message) {
        

        String accessToken = jwtService.generateAccessToken(user);

        RefreshToken refreshTokenEntity = refreshTokenService.createRefreshToken(user);
        String refreshToken = refreshTokenEntity.getToken();

        UserDto userDto = UserDto.builder()
            .id(user.getId())
            .name(user.getName())
            .email(user.getEmail())
            .phoneNumber(user.getPhoneNumber())
            .profilePicture(user.getProfilePicture())
            .gender(user.getGender())
            .occupation(user.getOccupation())
            .organization(user.getOrganization())
            .jobTitle(user.getJobTitle())
            .education(user.getEducation())
            .interests(user.getInterests())
            .industry(user.getIndustry())
            .bio(user.getBio())
            .build();

        

        AuthResponse authResponse = new AuthResponse(
                userDto,
                accessToken,
                refreshToken,
                message
        );

                

        return authResponse;
    }

    @Transactional
    public UserDto updateUser(String email, User update) {
        User user = userRepository.findByEmail(email);
        if (user == null) throw new RuntimeException("User not found");

        if (update.getName() != null) user.setName(update.getName());
        if (update.getPhoneNumber() != null) user.setPhoneNumber(update.getPhoneNumber());
        if (update.getProfilePicture() != null) user.setProfilePicture(update.getProfilePicture());
        if (update.getGender() != null) user.setGender(update.getGender());
        if (update.getOccupation() != null) user.setOccupation(update.getOccupation());
        if (update.getOrganization() != null) user.setOrganization(update.getOrganization());
        if (update.getJobTitle() != null) user.setJobTitle(update.getJobTitle());
        if (update.getEducation() != null) user.setEducation(update.getEducation());
        if (update.getInterests() != null) user.setInterests(update.getInterests());
        if (update.getIndustry() != null) user.setIndustry(update.getIndustry());
        if (update.getBio() != null) user.setBio(update.getBio());
        if (update.getEmail() != null && !update.getEmail().equals(user.getEmail())) {
            if (userRepository.existsByEmail(update.getEmail())) throw new RuntimeException("Email already exists");
            user.setEmail(update.getEmail());
        }
        if (update.getPassword() != null && !update.getPassword().isEmpty()) {
            user.setPassword(passwordEncoder.encode(update.getPassword()));
        }

        user = userRepository.save(user);

        return UserDto.builder()
                .id(user.getId())
                .name(user.getName())
                .email(user.getEmail())
                .phoneNumber(user.getPhoneNumber())
            .profilePicture(user.getProfilePicture())
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

    @Transactional
    public void deleteUser(String email) {
        User user = userRepository.findByEmail(email);
        if (user == null) throw new RuntimeException("User not found");
        userRepository.delete(user);
    }

    @Transactional
    public AuthResponse refreshToken(String refreshTokenStr) {
        return refreshTokenService.findByToken(refreshTokenStr)
                .map(refreshTokenService::verifyExpiration)
                .map(RefreshToken::getUser)
                .map(user -> {
                    String accessToken = jwtService.generateAccessToken(user);
                    UserDto userDto = UserDto.builder()
                            .id(user.getId())
                            .name(user.getName())
                            .email(user.getEmail())                
                            .build();
                    return new AuthResponse(userDto, accessToken, refreshTokenStr, "Token refreshed successfully");
                })
                .orElseThrow(() -> new RuntimeException("Refresh token is not in database!"));
    }

    public void logout(String refreshTokenStr) {
        if (refreshTokenStr != null) {
            refreshTokenService.deleteByToken(refreshTokenStr);
        }
    }
}

