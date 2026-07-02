package com.accenture.intern.docmind.controller;

import com.accenture.intern.docmind.dto.chat.ChatRequest;
import com.accenture.intern.docmind.aiservices.chat.ChatService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping("/{id}/message")
    public ResponseEntity<com.accenture.intern.docmind.dto.chat.ChatJobResponse> submitMessage(@PathVariable Long id, @RequestBody ChatRequest request) {
        return ResponseEntity.ok(chatService.submitMessage(id, request));
    }

    @GetMapping(value = "/{sessionId}/stream/{messageId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<ServerSentEvent<String>>> streamChat(@PathVariable Long sessionId, @PathVariable Long messageId) {
        return ResponseEntity.ok()
                .header("Content-Type", "text/event-stream")
                .header("Cache-Control", "no-cache")
                .header("Connection", "keep-alive")
                .body(chatService.streamChat(messageId));
    }
}


