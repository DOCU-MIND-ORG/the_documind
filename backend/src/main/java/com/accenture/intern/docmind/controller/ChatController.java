package com.accenture.intern.docmind.controller;

import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

/**
 * Handles AI chat streaming endpoints.
 * Implementation to be completed by the assigned developer.
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    // POST /api/chat/{id}/stream
    @PostMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamChat(@PathVariable Long id,
                                                     @RequestBody(required = false) Object request) {
        // TODO: implement
        throw new UnsupportedOperationException("Not yet implemented");
    }
}
