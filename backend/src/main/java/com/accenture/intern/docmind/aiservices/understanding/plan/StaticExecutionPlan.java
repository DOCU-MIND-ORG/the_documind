package com.accenture.intern.docmind.aiservices.understanding.plan;

import com.accenture.intern.docmind.aiservices.understanding.RetrievalPlan;
import com.accenture.intern.docmind.aiservices.understanding.MergeOperation;
import java.util.List;

public record StaticExecutionPlan(
    List<RetrievalPlan> plans,
    MergeOperation mergeOperation
) implements ExecutionPlan {
    
    @Override
    public String executionTier() {
        return "DECOMPOSE";
    }
}
