package com.accenture.intern.docmind.controllers;

import com.accenture.intern.docmind.ai.ChatRequest;
import com.accenture.intern.docmind.ai.ChatResponse;
import com.accenture.intern.docmind.ai.GeminiChatService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/chat")
@CrossOrigin(origins = "*", maxAge = 3600)
public class ChatController {

    private final GeminiChatService chatService;

    public ChatController(GeminiChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest request) {
        String responseContent = chatService.chat(request);
        return ResponseEntity.ok(new ChatResponse(responseContent));
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamChat(@RequestBody ChatRequest request) {
        return chatService.streamChat(request);
    }
}
