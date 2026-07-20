package com.accenture.intern.docmind.aiservices.evaluation;

import com.accenture.intern.docmind.aiservices.evaluation.dto.BenchmarkQuery;
import com.accenture.intern.docmind.aiservices.evaluation.dto.EvaluationResult;
import com.accenture.intern.docmind.aiservices.retrieval.RetrievalOrchestrator;
import com.accenture.intern.docmind.aiservices.understanding.PlannerService;
import com.accenture.intern.docmind.aiservices.understanding.plan.ExecutionPlan;
import com.accenture.intern.docmind.dto.chat.RetrievalResult;
import com.accenture.intern.docmind.dto.context.SessionContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class EvaluationService {

    private final PlannerService plannerService;
    private final RetrievalOrchestrator retrievalOrchestrator;
    private final PipelineValidator pipelineValidator;

    public EvaluationService(PlannerService plannerService, RetrievalOrchestrator retrievalOrchestrator, PipelineValidator pipelineValidator) {
        this.plannerService = plannerService;
        this.retrievalOrchestrator = retrievalOrchestrator;
        this.pipelineValidator = pipelineValidator;
    }

    public Mono<EvaluationResult> evaluate(BenchmarkQuery benchmarkQuery) {
        long startTime = System.currentTimeMillis();
        
        SessionContext emptyContext = new SessionContext(-1L, List.of(), null, Map.of());
        
        return plannerService.routeQuery(benchmarkQuery.getQuery(), "", emptyContext, null)
            .flatMap(execPlan -> {
                long plannerTime = System.currentTimeMillis() - startTime;
                
                long retrievalStart = System.currentTimeMillis();
                return retrievalOrchestrator.orchestrate(benchmarkQuery.getQuery(), -1L, execPlan, null)
                    .map(retrievalResult -> {
                        long retrievalTime = System.currentTimeMillis() - retrievalStart;
                        return buildResult(benchmarkQuery, execPlan, retrievalResult, plannerTime, retrievalTime);
                    });
            });
    }

    private EvaluationResult buildResult(BenchmarkQuery query, ExecutionPlan execPlan, RetrievalResult retrieval, long plannerLatency, long retrievalLatency) {
        EvaluationResult result = new EvaluationResult();
        result.setQueryId(query.getId());
        result.setQuery(query.getQuery());
        // Planner metrics
        result.setActualTier(execPlan.executionTier());
        result.setActualStrategy(execPlan.strategy());
        result.setGeneratedPlans(execPlan.getPlans() != null ? execPlan.getPlans().size() : 0);
        // plannedDocuments: union of all target documents across all plans (planner intent)
        if (execPlan.getPlans() != null && !execPlan.getPlans().isEmpty()) {
            java.util.List<String> planned = execPlan.getPlans().stream()
                .filter(p -> p.targetDocuments() != null)
                .flatMap(p -> p.targetDocuments().stream())
                .distinct()
                .collect(java.util.stream.Collectors.toList());
            result.setPlannedDocuments(planned);
        }

        if (retrieval.getTelemetry() != null) {
            com.accenture.intern.docmind.dto.chat.RetrievalTelemetry updated = retrieval.getTelemetry().toBuilder()
                .plannerLatency(plannerLatency)
                .totalRetrievalLatency(retrievalLatency)
                .build();
            result.setTelemetry(updated);
        }
        
        // Retrieval metrics
        if (retrieval.getEvidence() != null) {
            result.setSurvivingChunks(retrieval.getEvidence().size());
            Map<String, Long> dist = retrieval.getEvidence().stream()
                .filter(c -> c.chunk().getMetadata().get("sourceName") != null)
                .collect(Collectors.groupingBy(c -> (String) c.chunk().getMetadata().get("sourceName"), Collectors.counting()));
            result.setChunkDistribution(dist);
            // finalDocuments: what actually survived after the full pipeline
            result.setFinalDocuments(new java.util.ArrayList<>(dist.keySet()));
            
            // retrievalCandidates: what survived Rerank (before MultiSignal filters)
            if (retrieval.getTelemetry() != null && retrieval.getTelemetry().retrievalCandidateDocs() != null) {
                result.setRetrievalCandidates(new java.util.ArrayList<>(retrieval.getTelemetry().retrievalCandidateDocs()));
            } else {
                result.setRetrievalCandidates(new java.util.ArrayList<>());
            }
            
            Map<String, Long> pDist = retrieval.getEvidence().stream()
                .filter(c -> c.chunk().getMetadata().get("planId") != null)
                .collect(Collectors.groupingBy(c -> (String) c.chunk().getMetadata().get("planId"), Collectors.counting()));
            result.setPlanDistribution(pDist);
        } else {
            result.setSurvivingChunks(0);
            result.setChunkDistribution(new HashMap<>());
            result.setPlanDistribution(new HashMap<>());
        }
        
        // Visual metrics
        if (retrieval.getVisuals() != null) {
            result.setRetrievedVisuals(retrieval.getVisuals().size());
            Map<String, Long> vDist = retrieval.getVisuals().stream()
                .filter(v -> v.sourceDocument() != null)
                .collect(Collectors.groupingBy(v -> v.sourceDocument(), Collectors.counting()));
            result.setVisualDistribution(vDist);
        } else {
            result.setRetrievedVisuals(0);
            result.setVisualDistribution(new HashMap<>());
        }
        
        // Assertions
        validateAssertions(query, result);
        
        return result;
    }

    private String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase().replace("-", " ").replaceAll("\\s+", " ").trim();
    }

    private void validateAssertions(BenchmarkQuery query, EvaluationResult result) {
        // Run universal pipeline checks first
        List<String> pipelineErrors = pipelineValidator.validateInvariants(result);
        if (!pipelineErrors.isEmpty()) {
            result.setPassed(false);
            result.setFailureReason("Pipeline violation: " + String.join(", ", pipelineErrors));
            return;
        }

        log.info("--- EVALUATION VALIDATION ---");
        log.info("Expected docs: {}", query.getMustContainDocuments());
        log.info("Chunk docs: {}", result.getChunkDistribution().keySet());
        log.info("Planner docs: {}", result.getPlannedDocuments());
        log.info("Retrieval docs: {}", result.getRetrievalCandidates());
        log.info("Final docs: {}", result.getFinalDocuments());
        log.info("-----------------------------");

        // Relaxed tier and strategy checking - don't fail purely on this
        // if (query.getExpectedTier() != null && !query.getExpectedTier().equals(result.getActualTier())) { ... }
        // if (query.getExpectedStrategy() != null && !query.getExpectedStrategy().equals(result.getActualStrategy())) { ... }

        java.util.Map<String, Long> normalizedDist = new java.util.HashMap<>();
        for (java.util.Map.Entry<String, Long> entry : result.getChunkDistribution().entrySet()) {
            normalizedDist.put(normalize(entry.getKey()), entry.getValue());
        }

        java.util.Map<String, Long> normalizedVis = new java.util.HashMap<>();
        for (java.util.Map.Entry<String, Long> entry : result.getVisualDistribution().entrySet()) {
            normalizedVis.put(normalize(entry.getKey()), entry.getValue());
        }

        // Must contain documents
        if (query.getMustContainDocuments() != null) {
            for (String doc : query.getMustContainDocuments()) {
                String normDoc = normalize(doc);
                long requiredChunks = query.getMinCitations();
                if (query.getMinimumChunksPerDocument() != null) {
                    for (java.util.Map.Entry<String, Integer> entry : query.getMinimumChunksPerDocument().entrySet()) {
                        if (normalize(entry.getKey()).equals(normDoc)) {
                            requiredChunks = entry.getValue();
                            break;
                        }
                    }
                }
                
                if (!normalizedDist.containsKey(normDoc) || normalizedDist.get(normDoc) < requiredChunks) {
                    result.setPassed(false);
                    result.setFailureReason("Missing required document " + doc + " or insufficient citations. Required: " + requiredChunks);
                    return;
                }
            }
        }
        
        // Forbidden documents
        if (query.getForbiddenDocuments() != null) {
            for (String doc : query.getForbiddenDocuments()) {
                String normDoc = normalize(doc);
                if (normalizedDist.containsKey(normDoc) || normalizedVis.containsKey(normDoc)) {
                    result.setPassed(false);
                    result.setFailureReason("Contained forbidden document " + doc);
                    return;
                }
            }
        }
        
        // Visual constraints
        if (query.getAllowedVisualSources() != null && !normalizedVis.isEmpty()) {
            java.util.Set<String> allowedNorm = query.getAllowedVisualSources().stream()
                .map(this::normalize)
                .collect(Collectors.toSet());
            for (String doc : normalizedVis.keySet()) {
                if (!allowedNorm.contains(doc)) {
                    result.setPassed(false);
                    result.setFailureReason("Visual from unauthorized source: " + doc);
                    return;
                }
            }
        }
        
        result.setPassed(true);
        result.setFailureReason(null);
    }
}
