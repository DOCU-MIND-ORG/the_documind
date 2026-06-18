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
import com.accenture.intern.docmind.entity.Otp;
import com.accenture.intern.docmind.repository.OtpRepository;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

@Service
@RequiredArgsConstructor
@Transactional
public class AuthService {

    private final UserRepository userRepository;
    private final OtpRepository otpRepository;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final JavaMailSender mailSender;

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

        System.out.println("OTP for " + email + " is: " + otp);
        sendOtpEmail(user.getEmail(), otp);
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
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(email);
        message.setSubject("DocuMind password reset OTP");
        message.setText(
                "Your DocuMind password reset OTP is: " + otp + "\n\n" +
                        "This OTP will expire in 10 minutes.\n\n" +
                        "If you did not request a password reset, please ignore this email."
        );

        mailSender.send(message);
    }

    private LoginResponse generateAuthResponse(User user, String message) {
        

        String accessToken = jwtService.generateAccessToken(user);

        RefreshToken refreshTokenEntity = refreshTokenService.createRefreshToken(user);
        String refreshToken = refreshTokenEntity.getToken();

        UserDto userDto = UserDto.builder()
                .id(user.getId())
                .name(user.getName())
                .email(user.getEmail())                
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
}
