package com.accenture.intern.docmind.aiservices.understanding.plan;

import com.accenture.intern.docmind.aiservices.understanding.RetrievalPlan;
import com.accenture.intern.docmind.aiservices.understanding.MergeOperation;
import com.accenture.intern.docmind.aiservices.understanding.EntityResolution;
import java.util.List;

public record AdaptiveExecutionPlan(
    String strategy,
    String goal,
    List<String> expectedEntities,
    int expectedSources,
    int maxIterations,
    RetrievalPlan initialRetrievalPlan,
    List<EntityResolution> entities,
    boolean visualSearch
) implements ExecutionPlan {
    
    @Override
    public String executionTier() {
        return "ADAPTIVE";
    }

    @Override
    public List<RetrievalPlan> getPlans() {
        return initialRetrievalPlan != null ? List.of(initialRetrievalPlan) : List.of();
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
