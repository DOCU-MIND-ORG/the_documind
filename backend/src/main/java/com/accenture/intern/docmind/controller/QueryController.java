package com.accenture.intern.docmind.controller;

import com.accenture.intern.docmind.dto.chat.RagResponse;
import com.accenture.intern.docmind.aiservices.ChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class QueryController {

    private final ChatService chatService;

    // This is the function which we are using currently to get response
    @PostMapping("/query")
    public ResponseEntity<Map<String, Object>> query(@RequestBody Map<String, String> body) {
        String question  = body.get("question");
        String sessionId = body.getOrDefault("sessionId", "default");

        if (question == null || question.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Field 'question' is required"));
        }

        try {
            Long sessionIdLong = "default".equals(sessionId) ? 1L : Long.parseLong(sessionId);
            RagResponse ragResponse = chatService.chatWithCitations(question, sessionIdLong);

            return ResponseEntity.ok(Map.of(
                    "answer",           ragResponse.answer(),
                    "citations",        ragResponse.citations(),
                    "foundInDocuments", ragResponse.foundInDocuments()
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }
}
