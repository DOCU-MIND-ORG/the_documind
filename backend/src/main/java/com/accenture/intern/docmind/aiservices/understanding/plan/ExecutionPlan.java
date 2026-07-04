package com.accenture.intern.docmind.aiservices.understanding.plan;

import com.accenture.intern.docmind.aiservices.understanding.RetrievalPlan;
import com.accenture.intern.docmind.aiservices.understanding.MergeOperation;
import com.accenture.intern.docmind.aiservices.understanding.EntityResolution;
import java.util.List;

public sealed interface ExecutionPlan permits 
    DirectExecutionPlan, 
    StaticExecutionPlan, 
    AdaptiveExecutionPlan {
    
    /**
     * @return The specific tier of execution for this plan.
     */
    String executionTier();

    /**
     * @return The routing strategy classified for this query.
     */
    String strategy();

    /**
     * @return The list of retrieval plans to execute.
     */
    List<RetrievalPlan> getPlans();

    /**
     * @return The merge operation to apply to the results of the plans.
     */
    MergeOperation getMergeOperation();

    /**
     * @return The list of entities extracted for this query.
     */
    List<EntityResolution> getEntities();

    /**
     * @return Whether the query asks for or would benefit from visual information.
     */
    boolean isVisualSearch();
}
