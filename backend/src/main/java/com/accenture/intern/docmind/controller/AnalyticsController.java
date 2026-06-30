package com.accenture.intern.docmind.controller;

import com.accenture.intern.docmind.entity.AnalyticsDaily;
import com.accenture.intern.docmind.repository.AnalyticsDailyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final AnalyticsDailyRepository analyticsDailyRepository;

    @GetMapping("/daily")
    public ResponseEntity<List<AnalyticsDaily>> getDailyAnalytics() {
        return ResponseEntity.ok(analyticsDailyRepository.findAll());
    }
}
