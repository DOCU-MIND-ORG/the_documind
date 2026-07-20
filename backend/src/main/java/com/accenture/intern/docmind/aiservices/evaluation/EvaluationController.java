package com.accenture.intern.docmind.aiservices.evaluation;

import com.accenture.intern.docmind.aiservices.evaluation.dto.BenchmarkQuery;
import com.accenture.intern.docmind.aiservices.evaluation.dto.EvaluationResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@RestController
@RequestMapping("/evaluation")
public class EvaluationController {

    private final EvaluationService evaluationService;
    private final BenchmarkRepository benchmarkRepository;
    private final ObjectMapper objectMapper;
    
    // In-memory storage for prototype
    private final ConcurrentHashMap<String, List<EvaluationResult>> historyStore = new ConcurrentHashMap<>();

    public EvaluationController(EvaluationService evaluationService, BenchmarkRepository benchmarkRepository, ObjectMapper objectMapper) {
        this.evaluationService = evaluationService;
        this.benchmarkRepository = benchmarkRepository;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/suites")
    public List<String> getSuites() {
        return benchmarkRepository.getAvailableSuites();
    }

    @GetMapping("/benchmark")
    public List<BenchmarkQuery> getBenchmark(@RequestParam(defaultValue = "regression") String profile) {
        return benchmarkRepository.getSuite(profile).getQueries();
    }

    @RequestMapping(value = "/run", method = {RequestMethod.GET, RequestMethod.POST})
    public Mono<Map<String, Object>> runEvaluation(@RequestParam(defaultValue = "regression") String profile) {
        String runId = UUID.randomUUID().toString();
        
        final List<BenchmarkQuery> finalQueries;
        if ("all".equalsIgnoreCase(profile)) {
            List<BenchmarkQuery> tempQueries = new ArrayList<>();
            for (String suiteName : benchmarkRepository.getAvailableSuites()) {
                List<BenchmarkQuery> suiteQueries = benchmarkRepository.getSuite(suiteName).getQueries();
                if (suiteQueries != null) {
                    tempQueries.addAll(suiteQueries);
                }
            }
            finalQueries = tempQueries;
        } else {
            finalQueries = benchmarkRepository.getSuite(profile).getQueries();
        }
        
        if (finalQueries == null || finalQueries.isEmpty()) {
            return Mono.just(Map.of("error", "No queries found for profile: " + profile));
        }
        
        // Run sequentially to avoid rate limits / overwhelming the local model
        return Flux.fromIterable(finalQueries)
            .concatMap(q -> evaluationService.evaluate(q))
            .collectList()
            .map(results -> {
                historyStore.put(runId, results);
                
                Map<String, Object> output = Map.of(
                    "runId", runId,
                    "profile", profile,
                    "totalQueries", finalQueries.size(),
                    "passed", results.stream().filter(EvaluationResult::isPassed).count(),
                    "results", results
                );
                
                // Save to local file
                try {
                    File dir = new File("evaluation-results");
                    if (!dir.exists()) dir.mkdirs();
                    File resultFile = new File(dir, "run-" + profile + "-" + runId + ".json");
                    objectMapper.writerWithDefaultPrettyPrinter().writeValue(resultFile, output);
                    log.info("Saved evaluation results to {}", resultFile.getAbsolutePath());
                } catch (Exception e) {
                    log.error("Failed to save evaluation results to file", e);
                }
                
                return output;
            });
    }

    @GetMapping("/results/{id}")
    public List<EvaluationResult> getResults(@PathVariable String id) {
        return historyStore.getOrDefault(id, new ArrayList<>());
    }

    @GetMapping("/history")
    public java.util.Map<String, Object> getHistory() {
        java.util.Map<String, Object> history = new java.util.HashMap<>();
        historyStore.forEach((runId, results) -> {
            long passed = results.stream().filter(EvaluationResult::isPassed).count();
            history.put(runId, Map.of(
                "total", results.size(),
                "passed", passed,
                "score", String.format("%.1f%%", (passed * 100.0) / results.size())
            ));
        });
        return history;
    }
}
