package com.accenture.intern.docmind.controller;

import com.accenture.intern.docmind.dto.session.CreateSessionRequest;
import com.accenture.intern.docmind.dto.session.RenameSessionRequest;
import com.accenture.intern.docmind.dto.session.SessionResponse;
import com.accenture.intern.docmind.dto.session.MessageResponse;
import com.accenture.intern.docmind.dto.session.SuggestedQuestionsResponse;
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
    public ResponseEntity<List<SessionResponse>> getAllSessions(
            Principal principal
    ) {
        List<SessionResponse> sessions=sessionService.getAllSessions(principal.getName());
        return ResponseEntity.ok(sessions);
    }
   

    // DELETE /api/sessions/{id}
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteSession(
            @PathVariable Long id,
            Principal principal
    ) {
        sessionService.deleteSession(principal.getName(),id);
        return ResponseEntity.noContent().build();
    }

    // PUT /api/sessions/{id}/rename
    @PutMapping("/{id}/rename")
    public ResponseEntity<SessionResponse> renameSession(
            @PathVariable Long id,
            @RequestBody RenameSessionRequest request,
            Principal principal
    ) {
        SessionResponse response = sessionService.renameSession(principal.getName(), id, request.getTitle());
        return ResponseEntity.ok(response);
    }

    // GET /api/sessions/{id}/messages
    @GetMapping("/{id}/messages")
    public ResponseEntity<List<MessageResponse>> getSessionMessages(
            @PathVariable Long id,
            Principal principal
    ) {
        List<MessageResponse> messages = sessionService.getSessionMessages(principal.getName(), id);
        return ResponseEntity.ok(messages);
    }

    // GET /api/sessions/{id}/suggested-questions
    // Polled by the frontend after a document/Wikipedia upload. Returns
    // status: NOT_STARTED | GENERATING | READY | FAILED, plus the 3 questions
    // once status is READY.
    @GetMapping("/{id}/suggested-questions")
    public ResponseEntity<SuggestedQuestionsResponse> getSuggestedQuestions(
            @PathVariable Long id,
            Principal principal
    ) {
        SuggestedQuestionsResponse response = sessionService.getSuggestedQuestions(principal.getName(), id);
        return ResponseEntity.ok(response);
    }
}
