package com.accenture.intern.docmind.controller;

import com.accenture.intern.docmind.dto.session.CreateSessionRequest;
import com.accenture.intern.docmind.dto.session.SessionResponse;
import com.accenture.intern.docmind.service.SessionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import lombok.RequiredArgsConstructor;

import java.security.Principal;
import java.util.List;

/**
 * Handles session management endpoints.
 * Implementation to be completed by the assigned developer.
 */
@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
public class SessionController {

    private final SessionService sessionService;

    // POST /api/sessions
    @PostMapping
    public ResponseEntity<SessionResponse> createSession(
            @RequestBody CreateSessionRequest request,
            Principal principal
    ){
        SessionResponse response=sessionService.createSession(principal.getName(),request);
        return ResponseEntity.ok(response);
    }

    // GET /api/sessions
    @GetMapping
    public ResponseEntity<List<SessionResponse>> getAllSessions() {
        // TODO: implement
        throw new UnsupportedOperationException("Not yet implemented");
    }

    // GET /api/sessions/{id}
    @GetMapping("/{id}")
    public ResponseEntity<SessionResponse> getSessionById(@PathVariable Long id) {
        // TODO: implement
        throw new UnsupportedOperationException("Not yet implemented");
    }

    // DELETE /api/sessions/{id}
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteSession(@PathVariable Long id) {
        // TODO: implement
        throw new UnsupportedOperationException("Not yet implemented");
    }
}
