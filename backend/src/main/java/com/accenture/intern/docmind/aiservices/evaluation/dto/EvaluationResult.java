package com.accenture.intern.docmind.aiservices.evaluation.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvaluationResult {
    private String queryId;
    private String query;
    private boolean passed;
    private String failureReason; // if passed == false

    // Planner Metrics — what the planner INTENDED to search
    private String actualTier;
    private String actualStrategy;
    private int generatedPlans;
    private java.util.List<String> plannedDocuments;   // from ExecutionPlan scope

    // Retrieval Metrics — what actually SURVIVED to the final context
    private java.util.List<String> retrievalCandidates; // from Rerank (pre-MultiSignal)
    private java.util.List<String> finalDocuments;      // from chunkDistribution.keySet() (post-MultiSignal)

    // Retrieval Metrics
    private int retrievedChunks;
    private int survivingChunks;
    private Map<String, Long> chunkDistribution; // sourceName -> count
    private Map<String, Long> planDistribution;  // planId -> count

    // Visual Metrics
    private int retrievedVisuals;
    private Map<String, Long> visualDistribution; // sourceName -> count

    private com.accenture.intern.docmind.dto.chat.RetrievalTelemetry telemetry;
}
