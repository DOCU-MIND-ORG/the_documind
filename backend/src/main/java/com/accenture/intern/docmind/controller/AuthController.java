package com.accenture.intern.docmind.controller;

import com.accenture.intern.docmind.dto.auth.LoginRequest;
import com.accenture.intern.docmind.dto.auth.LoginResponse;
import com.accenture.intern.docmind.dto.auth.SignupRequest;
import com.accenture.intern.docmind.dto.auth.UserUpdateDto;
import com.accenture.intern.docmind.entity.User;
import com.accenture.intern.docmind.repository.UserRepository;
import com.accenture.intern.docmind.security.JwtService;
import com.accenture.intern.docmind.service.AuthService;
import org.springframework.http.server.reactive.ServerHttpRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import com.accenture.intern.docmind.dto.auth.OtpRequest;
import com.accenture.intern.docmind.dto.auth.VerifyOtpRequest;
import com.accenture.intern.docmind.dto.auth.ResetPasswordRequest;

import java.util.HashMap;
import java.util.Map;


@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService AuthService;
    private final JwtService jwtService;
    private final UserRepository userRepository;



   
    @PostMapping("/signup")
    public ResponseEntity<?> SignUp(@RequestBody SignupRequest request) {
        try {
            LoginResponse response = AuthService.SignUp(request);
            return buildCookieResponse(response);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorResponse(e.getMessage()));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        try {
            LoginResponse response = AuthService.Login(request);
            return buildCookieResponse(response);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorResponse(e.getMessage()));
        }
    }

    @PostMapping("/request-otp")
    public ResponseEntity<?> requestOtp(@RequestBody OtpRequest request){
        try{
            AuthService.requestOtp(request.email());

            Map<String, String> body = new HashMap<>();
            body.put("message", "OTP sent to your email");

            return ResponseEntity.ok(body);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorResponse(e.getMessage()));
        }
    }

    @PostMapping("/verify-otp")
    public ResponseEntity<?> verifyOtp(@RequestBody VerifyOtpRequest request) {
        try {
            String verificationToken = AuthService.verifyOtp(request.email(), request.otp());

            Map<String, String> body = new HashMap<>();
            body.put("message", "OTP verified successfully");
            body.put("verificationToken", verificationToken);

            return ResponseEntity.ok(body);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorResponse(e.getMessage()));
        }
    }

    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@RequestBody ResetPasswordRequest request) {
        try {
            AuthService.resetPassword(
                    request.email(),
                    request.newPassword(),
                    request.verificationToken()
            );

            java.util.Map<String, String> body = new java.util.HashMap<>();
            body.put("message", "Password reset successfully");

            return ResponseEntity.ok(body);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorResponse(e.getMessage()));
        }
    }

   
    @PostMapping("/logout")
    public ResponseEntity<?> logout(ServerHttpRequest request) {
        String refreshToken = extractCookieValue(request, "refresh_token");
        AuthService.logout(refreshToken);

        ResponseCookie clearAccess = ResponseCookie.from("access_token", "")
                .httpOnly(true)
                .secure(true)
                .path("/")
                .maxAge(0)           // Expires immediately
                .sameSite("None")
                .build();

        ResponseCookie clearRefresh = ResponseCookie.from("refresh_token", "")
                .httpOnly(true)
                .secure(true)
                .path("/")
                .maxAge(0)
                .sameSite("None")
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, clearAccess.toString())
                .header(HttpHeaders.SET_COOKIE, clearRefresh.toString())
                .body(new java.util.HashMap<String, String>() {{ put("message", "Logged out successfully"); }});
    }

   
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(ServerHttpRequest request) {
        String refreshToken = extractCookieValue(request, "refresh_token");
        if (refreshToken == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorResponse("Refresh token is missing"));
        }
        try {
            LoginResponse response = AuthService.refreshToken(refreshToken);
            return buildCookieResponse(response);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorResponse(e.getMessage()));
        }
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(ServerHttpRequest request) {
        String token = extractCookieValue(request, "access_token");

        if (token == null || !jwtService.isTokenValid(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorResponse("No valid session found"));
        }

        String email = jwtService.extractEmail(token);
        User user = userRepository.findByEmail(email);

        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorResponse("User not found"));
        }

        com.accenture.intern.docmind.dto.auth.UserDto userDto =
                com.accenture.intern.docmind.dto.auth.UserDto.builder()
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

        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("user", userDto);
        body.put("message", "Session restored");

        return ResponseEntity.ok(body);
    }

    @DeleteMapping("/me")
    public ResponseEntity<?> deleteMe(ServerHttpRequest request) {
        String token = extractCookieValue(request, "access_token");

        if (token == null || !jwtService.isTokenValid(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorResponse("No valid session found"));
        }

        String email = jwtService.extractEmail(token);
        User user = userRepository.findByEmail(email);

        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorResponse("User not found"));
        }

        try {
            AuthService.deleteMe(user);
            
            // Clear the cookie
            ResponseCookie jwtCookie = ResponseCookie.from("access_token", "")
                    .httpOnly(true)
                    .secure(true) // Ensure this matches your deployment context
                    .path("/")
                    .maxAge(0)
                    .sameSite("None")
                    .build();

            return ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE, jwtCookie.toString())
                    .body(java.util.Map.of("message", "Account successfully deleted"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("Failed to delete account: " + e.getMessage()));
        }
    }

    @PutMapping("/update")
    public ResponseEntity<?> update(@RequestBody UserUpdateDto updateDto, ServerHttpRequest request) {
        String token = extractCookieValue(request, "access_token");

        if (token == null || !jwtService.isTokenValid(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorResponse("No valid session found"));
        }

        String email = jwtService.extractEmail(token);
        User user = userRepository.findByEmail(email);

        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorResponse("User not found"));
        }

        try {
            LoginResponse response = AuthService.updateProfileAndGetResponse(user, updateDto);
            return buildCookieResponse(response);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorResponse(e.getMessage()));
        }
    }

    @PutMapping("/update-profile-image")
    public ResponseEntity<?> updateProfileImage(@RequestBody com.accenture.intern.docmind.dto.auth.ProfileImageUpdateDto updateDto, ServerHttpRequest request) {
        String token = extractCookieValue(request, "access_token");

        if (token == null || !jwtService.isTokenValid(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorResponse("No valid session found"));
        }

        String email = jwtService.extractEmail(token);
        User user = userRepository.findByEmail(email);

        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorResponse("User not found"));
        }

        try {
            LoginResponse response = AuthService.updateProfileImageAndGetResponse(user, updateDto);
            return buildCookieResponse(response);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorResponse(e.getMessage()));
        }
    }

    

    private ResponseEntity<?> buildCookieResponse(LoginResponse response) {
        ResponseCookie jwtCookie = ResponseCookie.from("access_token", response.getAccessToken())
                .httpOnly(true)
                .secure(true) 
                .path("/")
                .maxAge(15 * 60)
                .sameSite("None")
                .build();

        ResponseCookie refreshCookie = ResponseCookie.from("refresh_token", response.getRefreshToken())
                .httpOnly(true)
                .secure(true)
                .path("/")
                .maxAge(7 * 24 * 60 * 60) 
                .sameSite("None")
                .build();

        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("user", response.getUser());
        body.put("accessToken", response.getAccessToken());
        body.put("message", response.getMessage());

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, jwtCookie.toString())
                .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
                .body(body);
    }

    
    private String extractCookieValue(ServerHttpRequest request, String name) {
        if (request.getCookies() == null) return null;
        HttpCookie cookie = request.getCookies().getFirst(name);
        if (cookie != null) {
            return cookie.getValue();
        }
        return null;
    }

    // Simple inline error DTO
    record ErrorResponse(String message) {}
}
