package com.accenture.intern.docmind.dto.auth;

import lombok.Data;

@Data
public class SignupRequest {
    private String name;
    private String email;
    private String password;
    private String phoneNumber;
}
