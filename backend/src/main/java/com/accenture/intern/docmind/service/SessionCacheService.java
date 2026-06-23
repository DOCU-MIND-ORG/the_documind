package com.accenture.intern.docmind.service;

import com.accenture.intern.docmind.dto.chat.SessionUploadState;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class SessionCacheService {

    // Cache with time-based expiration of 5 minutes and max size of 50
    private final Cache<Long, SessionUploadState> cache = Caffeine.newBuilder()
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .maximumSize(50)
            .build();

    public SessionUploadState getOrCreateState(Long sessionId) {
        return cache.get(sessionId, k -> new SessionUploadState());
    }

    public SessionUploadState getState(Long sessionId) {
        return cache.getIfPresent(sessionId);
    }
    
    public void invalidateState(Long sessionId) {
        cache.invalidate(sessionId);
    }
}
