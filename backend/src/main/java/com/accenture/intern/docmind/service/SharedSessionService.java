package com.accenture.intern.docmind.service;

import com.accenture.intern.docmind.dto.session.MessageResponse;
import com.accenture.intern.docmind.dto.session.SharedSessionResponse;
import com.accenture.intern.docmind.entity.*;
import com.accenture.intern.docmind.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class SharedSessionService {

    private final SharedSessionRepository sharedSessionRepository;
    private final SessionRepository sessionRepository;
    private final UserRepository userRepository;
    private final MessageRepository messageRepository;

    public SharedSessionResponse createSharedSession(Long mainSessionId, String userEmail) {
        User user = userRepository.findByEmail(userEmail);
        if (user == null) {
            throw new RuntimeException("User not found 🚫");
        }

        Session session = sessionRepository.findById(mainSessionId)
                .orElseThrow(() -> new RuntimeException("Session not found 🚫"));

        if (!session.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("Access Denied !! You seem to be 👽");
        }

        LocalDateTime now = LocalDateTime.now();

        SharedSession sharedSession = SharedSession.builder()
                .title(session.getTitle())
                .createdAt(now)
                .shareToken(UUID.randomUUID().toString())
                .user(user)
                .session(session)
                .build();

        List<Message> originalMessages = messageRepository.findBySessionOrderByCreatedAtAsc(session);
        List<SharedMessage> sharedMessages = originalMessages.stream()
                .map(msg -> SharedMessage.builder()
                        .sharedSession(sharedSession)
                        .role(msg.getRole())
                        .content(msg.getContent())
                        .createdAt(msg.getCreatedAt())
                        .build())
                .collect(Collectors.toList());

        sharedSession.setMessages(sharedMessages);
        SharedSession saved = sharedSessionRepository.save(sharedSession);

        return mapToResponse(saved);
    }

    public SharedSessionResponse getSharedSession(String shareToken) {
        SharedSession sharedSession = sharedSessionRepository.findByShareToken(shareToken)
                .orElseThrow(() -> new RuntimeException("Shared session not found 🚫"));

        return mapToResponse(sharedSession);
    }

    private SharedSessionResponse mapToResponse(SharedSession sharedSession) {
        List<MessageResponse> messageResponses = sharedSession.getMessages().stream()
                .map(msg -> MessageResponse.builder()
                        .id(String.valueOf(msg.getId()))
                        .role(msg.getRole())
                        .text(msg.getContent())
                        .createdAt(msg.getCreatedAt())
                        .build())
                .collect(Collectors.toList());

        return SharedSessionResponse.builder()
                .id(sharedSession.getShareToken())
                .title(sharedSession.getTitle())
                .messages(messageResponses)
                .createdAt(sharedSession.getCreatedAt())
                .build();
    }
}
