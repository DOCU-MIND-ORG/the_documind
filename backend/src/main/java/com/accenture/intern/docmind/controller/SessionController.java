package com.accenture.intern.docmind.controller;

import com.accenture.intern.docmind.dto.session.CreateSessionRequest;
import com.accenture.intern.docmind.dto.session.SessionResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Handles session management endpoints.
 * Implementation to be completed by the assigned developer.
 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    // POST /api/sessions
    @PostMapping
    public ResponseEntity<SessionResponse> createSession(@RequestBody CreateSessionRequest request) {
        // TODO: implement
        throw new UnsupportedOperationException("Not yet implemented");
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
