package com.accenture.intern.docmind.dto.auth;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class LoginResponse {

    private UserDto user;
    private String accessToken;
    private String refreshToken;
    private String message;
}
