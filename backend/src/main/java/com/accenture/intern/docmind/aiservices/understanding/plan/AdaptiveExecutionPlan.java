package com.accenture.intern.docmind.aiservices.understanding.plan;

import java.util.List;

public record AdaptiveExecutionPlan(
    String goal,
    List<String> expectedEntities,
    int expectedSources,
    int maxIterations
) implements ExecutionPlan {
    
    @Override
    public String executionTier() {
        return "ADAPTIVE";
    }
}
