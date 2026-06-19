package com.accenture.intern.docmind.controller;

import com.accenture.intern.docmind.dto.session.SharedSessionResponse;
import com.accenture.intern.docmind.service.SharedSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

@RestController
@RequestMapping("/api/shared-sessions")
@RequiredArgsConstructor
public class SharedSessionController {

    private final SharedSessionService sharedSessionService;

    @PostMapping("/{sessionId}")
    public ResponseEntity<SharedSessionResponse> shareSession(
            @PathVariable Long sessionId,
            Principal principal
    ) {
        SharedSessionResponse response = sharedSessionService.createSharedSession(sessionId, principal.getName());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/public/{token}")
    public ResponseEntity<SharedSessionResponse> getPublicSharedSession(
            @PathVariable String token
    ) {
        SharedSessionResponse response = sharedSessionService.getSharedSession(token);
        return ResponseEntity.ok(response);
    }
}
