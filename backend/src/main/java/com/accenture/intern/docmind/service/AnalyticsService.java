package com.accenture.intern.docmind.service;

import com.accenture.intern.docmind.entity.DailyUserVisit;
import com.accenture.intern.docmind.entity.DailyUserVisitId;
import com.accenture.intern.docmind.repository.AnalyticsDailyRepository;
import com.accenture.intern.docmind.repository.DailyUserVisitRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalyticsService {

    private final AnalyticsDailyRepository analyticsDailyRepository;
    private final DailyUserVisitRepository dailyUserVisitRepository;

    public void recordVisit(Long userId) {
        if (userId == null) return;
        
        LocalDate today = LocalDate.now();
        DailyUserVisitId id = new DailyUserVisitId(today, userId);
        
        if (!dailyUserVisitRepository.existsById(id)) {
            try {
                dailyUserVisitRepository.save(new DailyUserVisit(today, userId));
                analyticsDailyRepository.incrementUniqueVisitors(today);
            } catch (Exception e) {
                // Ignore constraint violation if two requests for the same user happen at the exact same millisecond
                log.warn("Could not record daily visit for user {}", userId);
            }
        }
    }

    public void incrementChatRequests() {
        try {
            analyticsDailyRepository.incrementChatRequests(LocalDate.now());
        } catch (Exception e) {
            log.error("Failed to increment chat requests", e);
        }
    }

    public void incrementDocumentsUploaded(int count) {
        if (count <= 0) return;
        try {
            analyticsDailyRepository.incrementDocumentsUploaded(LocalDate.now(), count);
        } catch (Exception e) {
            log.error("Failed to increment documents uploaded", e);
        }
    }

    public void incrementPdfExports() {
        try {
            analyticsDailyRepository.incrementPdfExports(LocalDate.now());
        } catch (Exception e) {
            log.error("Failed to increment pdf exports", e);
        }
    }

    public void recordLlmGeneration(long tokens, long durationMs) {
        try {
            analyticsDailyRepository.recordLlmGeneration(LocalDate.now(), tokens, durationMs);
        } catch (Exception e) {
            log.error("Failed to record LLM generation analytics", e);
        }
    }
}
