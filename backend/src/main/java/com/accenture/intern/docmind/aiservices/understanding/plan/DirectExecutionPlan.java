package com.accenture.intern.docmind.aiservices.understanding.plan;

import com.accenture.intern.docmind.aiservices.understanding.RetrievalPlan;
import com.accenture.intern.docmind.aiservices.understanding.MergeOperation;
import com.accenture.intern.docmind.aiservices.understanding.EntityResolution;
import java.util.List;

public record DirectExecutionPlan(
    String strategy,
    List<RetrievalPlan> plans,
    MergeOperation mergeOperation,
    List<EntityResolution> entities,
    boolean visualSearch,
    String imageType,
    String reason,
    String purpose
) implements ExecutionPlan {

    public DirectExecutionPlan(String strategy, RetrievalPlan plan, boolean visualSearch, String imageType, String reason, String purpose) {
        this(strategy, List.of(plan), MergeOperation.UNION, List.of(), visualSearch, imageType, reason, purpose);
    }

    public DirectExecutionPlan(String strategy, RetrievalPlan plan, List<EntityResolution> entities, boolean visualSearch, String imageType, String reason, String purpose) {
        this(strategy, List.of(plan), MergeOperation.UNION, entities, visualSearch, imageType, reason, purpose);
    }

    @Override
    public String executionTier() {
        return "DIRECT";
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

    @Override
    public String getImageType() {
        return imageType;
    }
}
