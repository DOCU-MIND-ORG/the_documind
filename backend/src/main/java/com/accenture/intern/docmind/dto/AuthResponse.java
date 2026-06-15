package com.accenture.intern.docmind.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class AuthResponse {

    private UserDto user;
    private String accessToken;
    private String refreshToken;
    private String message;
}
