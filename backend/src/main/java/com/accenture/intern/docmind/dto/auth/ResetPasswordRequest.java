package com.accenture.intern.docmind.dto.auth;

public record ResetPasswordRequest(
        String email,
        String newPassword,
        String verificationToken
) {
}
