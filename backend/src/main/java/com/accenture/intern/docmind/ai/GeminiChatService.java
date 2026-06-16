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

    // This should be used later for restructuring the user sent query
    public String chat(String request) {
        return this.chatClient.prompt()
                .user(request)
                .call()
                .content();
    }

    // This is for getting chatgpt style response
    public Flux<ServerSentEvent<String>> streamChat(String request) {
        return this.chatClient.prompt()
                .user(request)
                .stream()
                .content()
                .map(content -> ServerSentEvent.builder(content).build())
                .subscribeOn(Schedulers.boundedElastic());
    }
}
