package com.accenture.intern.docmind.service;

import com.accenture.intern.docmind.dto.auth.LoginResponse;
import com.accenture.intern.docmind.dto.auth.LoginRequest;
import com.accenture.intern.docmind.dto.auth.SignupRequest;
import com.accenture.intern.docmind.dto.auth.UserDto;
import com.accenture.intern.docmind.entity.*;
import com.accenture.intern.docmind.repository.UserRepository;
import com.accenture.intern.docmind.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.accenture.intern.docmind.repository.OtpRepository;
import com.accenture.intern.docmind.repository.SessionRepository;
import com.accenture.intern.docmind.repository.UserPreferenceRepository;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.UUID;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

@Service
@RequiredArgsConstructor
@Transactional
public class AuthService {

    private final UserRepository userRepository;
    private final OtpRepository otpRepository;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final JavaMailSender mailSender;
    private final CloudinaryService cloudinaryService;
    private final SessionService sessionService;
    private final SessionRepository sessionRepository;
    private final UserPreferenceRepository userPreferenceRepository;

    @Value("${spring.mail.username}")
    private String mailUsername;

    private final SecureRandom secureRandom = new SecureRandom();

    private final BCryptPasswordEncoder passwordEncoder =
            new BCryptPasswordEncoder();

    public LoginResponse SignUp(SignupRequest request) {

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

    public LoginResponse Login(LoginRequest request) {

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

    public void requestOtp(String email) {
        if (email == null || email.isBlank()) {
            throw new RuntimeException("Email is required");
        }

        User user = userRepository.findByEmail(email);

        if (user == null) {
            throw new RuntimeException("Email not found");
        }

        String otp = String.format("%06d", secureRandom.nextInt(1_000_000));

        otpRepository.deleteByUser(user);

        Otp otpEntity = Otp.builder()
                .user(user)
                .otpHash(passwordEncoder.encode(otp))
                .expiresAt(LocalDateTime.now().plusMinutes(10))
                .verified(false)
                .build();

        otpRepository.save(otpEntity);

        //System.out.println("OTP for " + email + " is: " + otp);
        sendOtpEmail(user.getEmail(), otp);
    }


    public LoginResponse updateProfileAndGetResponse(User user, com.accenture.intern.docmind.dto.auth.UserUpdateDto updateDto) {
        User updatedUser = updateProfile(user, updateDto);
        return generateAuthResponse(updatedUser, "Profile updated successfully");
    }

    private User updateProfile(User user, com.accenture.intern.docmind.dto.auth.UserUpdateDto dto) {
        if (dto.getName() != null) user.setName(dto.getName());
        if (dto.getPhoneNumber() != null) user.setPhoneNumber(dto.getPhoneNumber());
        if (dto.getGender() != null) user.setGender(dto.getGender());
        if (dto.getOccupation() != null) user.setOccupation(dto.getOccupation());
        if (dto.getOrganization() != null) user.setOrganization(dto.getOrganization());
        if (dto.getJobTitle() != null) user.setJobTitle(dto.getJobTitle());
        if (dto.getEducation() != null) user.setEducation(dto.getEducation());
        if (dto.getInterests() != null) user.setInterests(dto.getInterests());
        if (dto.getIndustry() != null) user.setIndustry(dto.getIndustry());
        if (dto.getBio() != null) user.setBio(dto.getBio());

        return userRepository.save(user);
    }

    public LoginResponse updateProfileImageAndGetResponse(User user, com.accenture.intern.docmind.dto.auth.ProfileImageUpdateDto dto) {
        if (dto.getLink() != null && !dto.getLink().isEmpty()) {
            if (user.getProfileImagePublicId() != null && !user.getProfileImagePublicId().equals(dto.getPublic_id())) {
                cloudinaryService.deleteImage(user.getProfileImagePublicId());
            }
            user.setProfileImageUrl(dto.getLink());
            user.setProfileImagePublicId(dto.getPublic_id());
            userRepository.save(user);
        }
        return generateAuthResponse(user, "Profile image updated successfully");
    }
    

    public String verifyOtp(String email,String otp){

        if(email==null || email.isBlank()){
            throw new RuntimeException("Email is required 🚫");
        }
        if(otp==null || otp.isBlank()){
            throw new RuntimeException("Otp is required 🚫");
        }

        User user=userRepository.findByEmail(email);

        if(user==null){
            throw new RuntimeException("Email is Found 🚫");
        }

        Otp otpEntity=otpRepository.findTopByUserOrderByIdDesc(user)
                .orElseThrow(()-> new RuntimeException("OTP not found. Please request a new OTP"));

        if (otpEntity.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new RuntimeException("OTP expired. Please request a new OTP");
        }

        if (!passwordEncoder.matches(otp, otpEntity.getOtpHash())) {
            throw new RuntimeException("Invalid OTP");
        }

        String verificationToken=UUID.randomUUID().toString();

        otpEntity.setVerified(true);
        otpEntity.setVerificationToken(verificationToken);
        otpEntity.setVerificationExpiresAt(LocalDateTime.now().plusMinutes(10));

        otpRepository.save(otpEntity);

        return verificationToken;
    }

    public void resetPassword(String email, String newPassword, String verificationToken) {
        if (email == null || email.isBlank()) {
            throw new RuntimeException("Email is required");
        }

        if (newPassword == null || newPassword.length() < 8) {
            throw new RuntimeException("Password must be at least 8 characters");
        }

        if (verificationToken == null || verificationToken.isBlank()) {
            throw new RuntimeException("OTP verification is required");
        }

        User user = userRepository.findByEmail(email);

        if (user == null) {
            throw new RuntimeException("Email not found");
        }

        Otp otpEntity = otpRepository.findByVerificationToken(verificationToken)
                .orElseThrow(() -> new RuntimeException("OTP verification is invalid"));

        if (!otpEntity.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("OTP verification is invalid");
        }

        if (!Boolean.TRUE.equals(otpEntity.getVerified())) {
            throw new RuntimeException("OTP is not verified");
        }

        if (otpEntity.getVerificationExpiresAt().isBefore(LocalDateTime.now())) {
            throw new RuntimeException("OTP verification expired. Please request a new OTP");
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        otpRepository.deleteByUser(user);
    }

    private void sendOtpEmail(String email, String otp) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(new InternetAddress(mailUsername, "Documind"));
            helper.setTo(email);
            helper.setSubject("Documind password reset OTP");
            helper.setText(buildOtpEmailHtml(otp), true);

            mailSender.send(message);
        } catch (MessagingException | java.io.UnsupportedEncodingException ex) {
            throw new RuntimeException("Failed to send OTP email", ex);
        }
    }

    private String buildOtpEmailHtml(String otp) {
        return """
                <!doctype html>
                <html lang="en">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>Documind OTP</title>
                </head>
                <body style="margin:0;padding:0;background:#f3f6fb;font-family:Arial,Helvetica,sans-serif;color:#172033;">
                    <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" style="background:#f3f6fb;padding:32px 16px;">
                        <tr>
                            <td align="center">
                                <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" style="max-width:560px;background:#ffffff;border-radius:18px;overflow:hidden;border:1px solid #e3e8f2;box-shadow:0 18px 45px rgba(23,32,51,0.08);">
                                    <tr>
                                        <td style="padding:28px 32px;background:#172033;">
                                            <table role="presentation" cellspacing="0" cellpadding="0">
                                                <tr>
                                                    <td style="width:46px;height:46px;border-radius:14px;background:#2fd6a2;color:#172033;font-size:22px;font-weight:800;text-align:center;line-height:46px;">D</td>
                                                    <td style="padding-left:14px;color:#ffffff;font-size:24px;font-weight:800;letter-spacing:0;">Documind</td>
                                                </tr>
                                            </table>
                                        </td>
                                    </tr>
                                    <tr>
                                        <td style="padding:34px 32px 12px;">
                                            <h1 style="margin:0 0 12px;font-size:24px;line-height:1.3;color:#172033;">Password reset verification</h1>
                                            <p style="margin:0;color:#59667a;font-size:15px;line-height:1.7;">Use this one-time password to continue resetting your Documind account password.</p>
                                        </td>
                                    </tr>
                                    <tr>
                                        <td style="padding:18px 32px 8px;">
                                            <div style="background:#f7fafc;border:1px solid #dce5ef;border-radius:14px;padding:22px;text-align:center;">
                                                <div style="font-size:12px;line-height:1;color:#728096;text-transform:uppercase;font-weight:700;letter-spacing:1.4px;margin-bottom:12px;">Your OTP</div>
                                                <div style="font-size:36px;line-height:1;font-weight:800;color:#172033;letter-spacing:8px;">%s</div>
                                            </div>
                                        </td>
                                    </tr>
                                    <tr>
                                        <td style="padding:18px 32px 34px;">
                                            <p style="margin:0 0 12px;color:#59667a;font-size:14px;line-height:1.7;">This OTP expires in <strong style="color:#172033;">10 minutes</strong>.</p>
                                            <p style="margin:0;color:#8a95a6;font-size:13px;line-height:1.6;">If you did not request a password reset, you can safely ignore this email.</p>
                                        </td>
                                    </tr>
                                </table>
                            </td>
                        </tr>
                    </table>
                </body>
                </html>
                """.formatted(otp);
    }

    private LoginResponse generateAuthResponse(User user, String message) {
        

        String accessToken = jwtService.generateAccessToken(user);

        RefreshToken refreshTokenEntity = refreshTokenService.createRefreshToken(user);
        String refreshToken = refreshTokenEntity.getToken();

        UserDto userDto = UserDto.builder()
                .id(user.getId())
                .name(user.getName())
                .email(user.getEmail())
                .phoneNumber(user.getPhoneNumber())
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

        

        LoginResponse authResponse = new LoginResponse(
                userDto,
                accessToken,
                refreshToken,
                message
        );

                

        return authResponse;
    }

    public LoginResponse refreshToken(String refreshTokenStr) {
        return refreshTokenService.findByToken(refreshTokenStr)
                .map(refreshTokenService::verifyExpiration)
                .map(RefreshToken::getUser)
                .map(user -> {
                    String accessToken = jwtService.generateAccessToken(user);
                    UserDto userDto = UserDto.builder()
                            .id(user.getId())
                            .name(user.getName())
                            .email(user.getEmail())
                            .phoneNumber(user.getPhoneNumber())
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
                    return new LoginResponse(userDto, accessToken, refreshTokenStr, "Token refreshed successfully");
                })
                .orElseThrow(() -> new RuntimeException("Refresh token is not in database!"));
    }


    public void logout(String refreshTokenStr) {
        if (refreshTokenStr != null) {
            refreshTokenService.deleteByToken(refreshTokenStr);
        }
    }

    public void deleteMe(User user) {
        // 1. Delete all sessions (this cascades to messages, attachments, Pinecone, and Cloudinary for attachments)
        java.util.List<Session> sessions = sessionRepository.findByUser(user);
        for (Session session : sessions) {
            try {
                sessionService.deleteSession(user.getEmail(), session.getSessionId());
            } catch (Exception e) {
                System.err.println("Failed to delete session " + session.getSessionId() + ": " + e.getMessage());
            }
        }

        // 2. Delete profile image from Cloudinary if it exists
        if (user.getProfileImagePublicId() != null && !user.getProfileImagePublicId().isEmpty()) {
            try {
                cloudinaryService.deleteImage(user.getProfileImagePublicId());
            } catch (Exception e) {
                System.err.println("Failed to delete profile image from Cloudinary: " + e.getMessage());
            }
        }

        // 3. Delete user preferences
        userPreferenceRepository.deleteByUser(user);

        // 4. Delete OTP and RefreshTokens
        otpRepository.deleteByUser(user);
        refreshTokenService.deleteByUser(user);

        userRepository.delete(user);
    }
}
