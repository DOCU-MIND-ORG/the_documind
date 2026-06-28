package com.accenture.intern.docmind.aiservices.understanding.plan;

import com.accenture.intern.docmind.aiservices.understanding.RetrievalPlan;

public record DirectExecutionPlan(
    RetrievalPlan retrievalPlan
) implements ExecutionPlan {
    
    @Override
    public String executionTier() {
        return "DIRECT";
    }
}
