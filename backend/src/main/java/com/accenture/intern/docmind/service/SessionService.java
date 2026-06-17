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
import java.util.ArrayList;
import java.util.List;

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

    public List<SessionResponse> getAllSessions(String userEmail){
        User user=userRepository.findByEmail(userEmail);

        if(user==null) throw new RuntimeException("User Not Found 🚫");

        List<Session> sessions=sessionRepository.findByUser(user);

        List<SessionResponse> response=new ArrayList<>();
        for(Session s:sessions) response.add(mapToResponse(s));
        return response;
    }

    public SessionResponse getSessionById(String userEmail,Long sessionId){
        User user=userRepository.findByEmail(userEmail);

        if(user==null) throw new RuntimeException("User Not Found 🚫");

        Session session=sessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("No Session found 🚫"));

        if(!session.getUser().getId().equals(user.getId())){
            throw new RuntimeException("Access Denied !! You seems to be 👽");
        }
        return mapToResponse(session);
    }

    public void deleteSession(String userEmail,Long sessionId){

        User user=userRepository.findByEmail(userEmail);

        if(user==null) throw new RuntimeException("User Not Found 🚫");

        Session session=sessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("Session Not Found"));

        if(!session.getUser().getId().equals(user.getId())){
            throw new RuntimeException("Access denied");
        }

        sessionRepository.delete(session);
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
