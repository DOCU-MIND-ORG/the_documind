package com.accenture.intern.docmind.aiservices.understanding.plan;

import com.accenture.intern.docmind.aiservices.understanding.RetrievalPlan;
import com.accenture.intern.docmind.aiservices.understanding.MergeOperation;
import com.accenture.intern.docmind.aiservices.understanding.EntityResolution;
import java.util.List;

public record DirectExecutionPlan(
    RetrievalPlan retrievalPlan,
    List<EntityResolution> entities,
    boolean visualSearch
) implements ExecutionPlan {
    
    @Override
    public String executionTier() {
        return "DIRECT";
    }

    @Override
    public List<RetrievalPlan> getPlans() {
        return List.of(retrievalPlan);
    }

    @Override
    public MergeOperation getMergeOperation() {
        return MergeOperation.NONE;
    }

    @Override
    public List<EntityResolution> getEntities() {
        return entities != null ? entities : List.of();
    }

    @Override
    public boolean isVisualSearch() {
        return visualSearch;
    }
}
