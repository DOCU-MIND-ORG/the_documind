package com.accenture.intern.docmind.aiservices.evaluation;

import com.accenture.intern.docmind.aiservices.evaluation.dto.EvaluationResult;
import com.accenture.intern.docmind.dto.chat.RetrievalTelemetry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class PipelineValidator {

    public List<String> validateInvariants(EvaluationResult result) {
        List<String> errors = new ArrayList<>();
        
        // 1. Check known issue status
        // If there's a known issue, we don't necessarily fail it here, but the evaluation runner might flag it differently.

        // 2. Validate Telemetry completeness
        RetrievalTelemetry telemetry = result.getTelemetry();
        if (telemetry == null) {
            errors.add("Missing telemetry data in EvaluationResult");
            return errors;
        }

        if (telemetry.denseLatency() < 0 || telemetry.keywordLatency() < 0 || telemetry.rerankLatency() < 0) {
            errors.add("Invalid (negative) latency detected in telemetry");
        }

        // 3. Final Hits Consistency
        if (telemetry.finalHits() != result.getSurvivingChunks()) {
            errors.add("Telemetry finalHits (" + telemetry.finalHits() + ") does not match survivingChunks (" + result.getSurvivingChunks() + ")");
        }

        // 4. Plan ID verification
        // Check that the sum of chunk counts in planDistribution equals survivingChunks
        long chunksWithPlan = 0;
        if (result.getPlanDistribution() != null) {
            for (Long count : result.getPlanDistribution().values()) {
                chunksWithPlan += count;
            }
        }
        
        if (result.getSurvivingChunks() > 0 && chunksWithPlan != result.getSurvivingChunks()) {
            errors.add("Only " + chunksWithPlan + " out of " + result.getSurvivingChunks() + " chunks have a planId.");
        }
        
        return errors;
    }
}
