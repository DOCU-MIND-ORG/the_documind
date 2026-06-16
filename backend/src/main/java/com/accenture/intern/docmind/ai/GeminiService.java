package com.accenture.intern.docmind.ai;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

@Service
public class GeminiService {

    private final ChatClient chatClient;

    public GeminiService(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    public String chat(String message) {
        return this.chatClient.prompt()
                .user(message)
                .call()
                .content();
    }

    public Flux<ServerSentEvent<String>> streamChat(String message) {
        return this.chatClient.prompt()
                .user(message)
                .stream()
                .content()
                .map(content -> ServerSentEvent.builder(content).build())
                .subscribeOn(Schedulers.boundedElastic());
    }
}
