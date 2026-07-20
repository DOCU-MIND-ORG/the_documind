package com.accenture.intern.docmind.aiservices.evaluation;

import com.accenture.intern.docmind.aiservices.evaluation.dto.BenchmarkQuery;
import com.accenture.intern.docmind.aiservices.evaluation.dto.BenchmarkSuite;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Repository;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Repository
public class BenchmarkRepository {

    private final ObjectMapper objectMapper;
    private List<String> availableSuites = new ArrayList<>();

    public BenchmarkRepository(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        loadManifest();
    }

    private void loadManifest() {
        try {
            ClassPathResource resource = new ClassPathResource("benchmarks/manifest.json");
            if (resource.exists()) {
                Map<String, Object> manifest = objectMapper.readValue(resource.getInputStream(), new TypeReference<Map<String, Object>>() {});
                if (manifest.containsKey("suites")) {
                    List<String> suites = (List<String>) manifest.get("suites");
                    this.availableSuites.addAll(suites);
                }
            } else {
                log.warn("No manifest.json found in benchmarks directory.");
            }
        } catch (Exception e) {
            log.error("Failed to load benchmark manifest", e);
        }
    }

    public List<String> getAvailableSuites() {
        return availableSuites;
    }

    public BenchmarkSuite getSuite(String suiteName) {
        try {
            ClassPathResource resource = new ClassPathResource("benchmarks/" + suiteName + ".json");
            if (resource.exists()) {
                try (InputStream is = resource.getInputStream()) {
                    return objectMapper.readValue(is, BenchmarkSuite.class);
                }
            } else {
                log.error("Benchmark suite not found: {}", suiteName);
            }
        } catch (Exception e) {
            log.error("Failed to load benchmark suite: {}", suiteName, e);
        }
        return new BenchmarkSuite("unknown", "unknown", "Not Found", new ArrayList<>());
    }
}