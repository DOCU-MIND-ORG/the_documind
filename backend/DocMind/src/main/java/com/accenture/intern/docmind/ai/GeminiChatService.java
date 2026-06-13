package com.accenture.intern.docmind.ai;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

@Service
public class GeminiChatService {

    private final ChatClient chatClient;

    public GeminiChatService(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    public String chat(ChatRequest request) {
        return this.chatClient.prompt()
                .user(request.getMessage())
                .call()
                .content();
    }

    public Flux<ServerSentEvent<String>> streamChat(ChatRequest request) {
        return this.chatClient.prompt()
                .user(request.getMessage())
                .stream()
                .content()
                .map(content -> ServerSentEvent.builder(content).build())
                .subscribeOn(Schedulers.boundedElastic());
    }
}
