package com.accenture.intern.docmind.aiservices.understanding;

import java.util.List;
import java.util.UUID;

/**
 * Immutable blueprint representing a single distinct query for the retrieval engine.
 */
public record RetrievalPlan(
        String id,
        String purpose,
        String optimizedQuery,
        List<String> targetDocuments,
        RetrievalExecutionMode executionMode,
        Scope scope,
        boolean isStructuralQuery
) {
    /** Convenience constructor */
    public RetrievalPlan(String purpose, String optimizedQuery, List<String> targetDocuments, RetrievalExecutionMode executionMode, Scope scope, boolean isStructuralQuery) {
        this(UUID.randomUUID().toString(), purpose, optimizedQuery, targetDocuments, executionMode, scope, isStructuralQuery);
    }
    
    /** Backward compatibility constructor for existing tests/usage */
    public RetrievalPlan(String purpose, String optimizedQuery, List<String> targetDocuments, RetrievalExecutionMode executionMode, Scope scope) {
        this(UUID.randomUUID().toString(), purpose, optimizedQuery, targetDocuments, executionMode, scope, false);
    }

    public RetrievalPlan withGlobalScope() {
        return new RetrievalPlan(this.id, this.purpose, this.optimizedQuery, List.of(), this.executionMode, Scope.CORPUS, this.isStructuralQuery);
    }
}
