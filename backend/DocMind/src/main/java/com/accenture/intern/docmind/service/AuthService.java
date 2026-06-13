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

