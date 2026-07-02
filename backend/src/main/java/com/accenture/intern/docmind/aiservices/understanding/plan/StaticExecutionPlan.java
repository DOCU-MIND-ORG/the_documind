package com.accenture.intern.docmind.aiservices.understanding.plan;

import com.accenture.intern.docmind.aiservices.understanding.RetrievalPlan;
import com.accenture.intern.docmind.aiservices.understanding.MergeOperation;
import com.accenture.intern.docmind.aiservices.understanding.EntityResolution;
import java.util.List;

public record StaticExecutionPlan(
    List<RetrievalPlan> plans,
    MergeOperation mergeOperation,
    List<EntityResolution> entities,
    boolean visualSearch
) implements ExecutionPlan {
    
    @Override
    public String executionTier() {
        return "DECOMPOSE";
    }

    @Override
    public List<RetrievalPlan> getPlans() {
        return plans;
    }

    @Override
    public MergeOperation getMergeOperation() {
        return mergeOperation;
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
