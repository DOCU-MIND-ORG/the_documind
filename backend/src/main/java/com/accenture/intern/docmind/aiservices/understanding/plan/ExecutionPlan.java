package com.accenture.intern.docmind.aiservices.understanding.plan;

public sealed interface ExecutionPlan permits 
    DirectExecutionPlan, 
    StaticExecutionPlan, 
    AdaptiveExecutionPlan {
    
    /**
     * @return The specific tier of execution for this plan.
     */
    String executionTier();
}
