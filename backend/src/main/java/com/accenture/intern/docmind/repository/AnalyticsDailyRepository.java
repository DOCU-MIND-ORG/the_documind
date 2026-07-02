package com.accenture.intern.docmind.repository;

import com.accenture.intern.docmind.entity.AnalyticsDaily;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

public interface AnalyticsDailyRepository extends JpaRepository<AnalyticsDaily, LocalDate> {

    @Modifying
    @Transactional
    @Query(value = "INSERT INTO analytics_daily (date, unique_visitors, chat_requests, documents_uploaded, pdf_exports, tokens_generated, avg_response_ms, response_count) " +
            "VALUES (:date, 1, 0, 0, 0, 0, 0, 0) " +
            "ON CONFLICT (date) DO UPDATE SET unique_visitors = analytics_daily.unique_visitors + 1", nativeQuery = true)
    void incrementUniqueVisitors(LocalDate date);

    @Modifying
    @Transactional
    @Query(value = "INSERT INTO analytics_daily (date, unique_visitors, chat_requests, documents_uploaded, pdf_exports, tokens_generated, avg_response_ms, response_count) " +
            "VALUES (:date, 0, 1, 0, 0, 0, 0, 0) " +
            "ON CONFLICT (date) DO UPDATE SET chat_requests = analytics_daily.chat_requests + 1", nativeQuery = true)
    void incrementChatRequests(LocalDate date);

    @Modifying
    @Transactional
    @Query(value = "INSERT INTO analytics_daily (date, unique_visitors, chat_requests, documents_uploaded, pdf_exports, tokens_generated, avg_response_ms, response_count) " +
            "VALUES (:date, 0, 0, :count, 0, 0, 0, 0) " +
            "ON CONFLICT (date) DO UPDATE SET documents_uploaded = analytics_daily.documents_uploaded + :count", nativeQuery = true)
    void incrementDocumentsUploaded(LocalDate date, int count);

    @Modifying
    @Transactional
    @Query(value = "INSERT INTO analytics_daily (date, unique_visitors, chat_requests, documents_uploaded, pdf_exports, tokens_generated, avg_response_ms, response_count) " +
            "VALUES (:date, 0, 0, 0, 1, 0, 0, 0) " +
            "ON CONFLICT (date) DO UPDATE SET pdf_exports = analytics_daily.pdf_exports + 1", nativeQuery = true)
    void incrementPdfExports(LocalDate date);

    @Modifying
    @Transactional
    @Query(value = "INSERT INTO analytics_daily (date, unique_visitors, chat_requests, documents_uploaded, pdf_exports, tokens_generated, avg_response_ms, response_count) " +
            "VALUES (:date, 0, 0, 0, 0, :tokens, :durationMs, 1) " +
            "ON CONFLICT (date) DO UPDATE SET " +
            "tokens_generated = analytics_daily.tokens_generated + :tokens, " +
            "avg_response_ms = ((analytics_daily.avg_response_ms * analytics_daily.response_count) + :durationMs) / (analytics_daily.response_count + 1), " +
            "response_count = analytics_daily.response_count + 1", nativeQuery = true)
    void recordLlmGeneration(LocalDate date, long tokens, long durationMs);
}
