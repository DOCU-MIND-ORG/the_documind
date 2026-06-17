package com.accenture.intern.docmind.service;

import com.accenture.intern.docmind.dto.session.CreateSessionRequest;
import com.accenture.intern.docmind.dto.session.SessionResponse;
import com.accenture.intern.docmind.entity.Session;
import com.accenture.intern.docmind.entity.User;
import com.accenture.intern.docmind.repository.SessionRepository;
import com.accenture.intern.docmind.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Service for managing chat sessions and message history.
 * Implementation to be completed by the assigned developer.
 */
@Service
@RequiredArgsConstructor
public class SessionService {

    private final SessionRepository sessionRepository;
    private final UserRepository userRepository;

    public SessionResponse createSession(String userEmail, CreateSessionRequest request){
        User user=userRepository.findByEmail(userEmail);

        if(user==null) throw new RuntimeException("User Not Found 🚫");

        LocalDateTime now=LocalDateTime.now();

        Session session=Session.builder()
                .user(user)
                .title(request.getTitle())
                .archived(false)
                .createdAt(now)
                .updatedAt(now)
                .build();

        Session savedSession=sessionRepository.save(session);
        return mapToResponse(savedSession);
    }

    private SessionResponse mapToResponse(Session session) {
        return SessionResponse.builder()
                .sessionId(session.getSessionId())
                .title(session.getTitle())
                .archived(session.getArchived())
                .createdAt(session.getCreatedAt())
                .updatedAt(session.getUpdatedAt())
                .build();
    }
}
