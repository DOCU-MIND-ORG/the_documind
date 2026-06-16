package com.accenture.intern.docmind.controllers;

import com.accenture.intern.docmind.ai.RagChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class QueryController {

    private final RagChatService ragChatService;

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
            RagChatService.RagResponse ragResponse =
                ragChatService.chatWithCitations(question, sessionId);

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
