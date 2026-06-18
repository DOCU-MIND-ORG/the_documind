package com.accenture.intern.docmind.service;

import com.accenture.intern.docmind.dto.chat.ChatRequest;
import com.accenture.intern.docmind.entity.Message;
import com.accenture.intern.docmind.entity.MessageRole;
import com.accenture.intern.docmind.entity.MessageStatus;
import com.accenture.intern.docmind.entity.Session;
import com.accenture.intern.docmind.repository.MessageRepository;
import com.accenture.intern.docmind.repository.SessionRepository;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;

@Service
@Transactional
public class ChatService {

    private final ChatClient chatClient;
    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;

    public ChatService(ChatClient.Builder chatClientBuilder,
            SessionRepository sessionRepository,
            MessageRepository messageRepository) {
        this.chatClient = chatClientBuilder.build();
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
    }

    public String chat(Long conversationId, ChatRequest request) {
        return this.chatClient.prompt()
                .user(request.getMessage())
                .call()
                .content();
    }

    public Flux<ServerSentEvent<String>> streamChat(Long conversationId, ChatRequest request) {
        Session session = sessionRepository.findById(conversationId)
                .orElseThrow(() -> new RuntimeException("Session not found"));

        // Save User Message immediately
        Message userMessage = Message.builder()
                .session(session)
                .role(MessageRole.USER)
                .content(request.getMessage())
                .status(MessageStatus.COMPLETE)
                .createdAt(LocalDateTime.now())
                .build();
        messageRepository.save(userMessage);

        StringBuilder aiResponseBuilder = new StringBuilder();

        return this.chatClient.prompt()
                .user(request.getMessage())
                .stream()
                .content()
                .doOnNext(content -> {
                    if (content != null) {
                        aiResponseBuilder.append(content);
                    }
                })
                .doOnComplete(() -> {
                    Message botMessage = Message.builder()
                            .session(session)
                            .role(MessageRole.ASSISTANT)
                            .content(aiResponseBuilder.toString())
                            .status(MessageStatus.COMPLETE)
                            .createdAt(LocalDateTime.now())
                            .build();
                    messageRepository.save(botMessage);
                })
                .map(content -> ServerSentEvent.builder(content).build())
                .subscribeOn(Schedulers.boundedElastic());
    }
}
