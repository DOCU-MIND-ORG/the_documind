package com.accenture.intern.docmind.aiservices.understanding;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Unified response envelope from the single LLM Router call.
 * Combines strategy classification + universal query expansion into one payload,
 * eliminating the need for a separate preprocessing round-trip.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LlmRoutingResponse(
    String strategy,
    String reason,

    @JsonProperty("is_bot_qa") Boolean isBotQa,

    /**
     * Key entities / nouns extracted from the query for downstream source filtering.
     */
    List<EntityResolution> entities,

    /**
     * The execution tier to route this query to:
     * DIRECT (one-shot), DECOMPOSE (static planning), or ADAPTIVE (iterative reasoning)
     */
    @JsonProperty("execution_tier") String executionTier,

    /**
     * Determines how evidence is retrieved:
     * RANKED (default, best chunks) or WHOLE_DOCUMENT (entire file).
     */
    @JsonProperty("retrieval_mode") String retrievalMode,

    /**
     * Determines how evidence from multiple plans is combined:
     * NONE (default), UNION (merge/dedupe), COMPARE (side-by-side).
     */
    @JsonProperty("merge_operation") String mergeOperation,

    /**
     * The unified execution plans to resolve this query.
     */
    List<Plan> plans,

    /**
     * Whether the query asks for or would benefit from visual information.
     */
    @JsonProperty("visual_search") Boolean visualSearch,

    /**
     * Optional image type (e.g. DIAGRAM, PHOTO, SCREENSHOT) extracted from the query.
     */
    @JsonProperty("image_type") String imageType
) {
    public record Plan(
        String purpose,
        @JsonProperty("optimized_query") String optimizedQuery,
        @JsonProperty("target_documents") List<String> targetDocuments,
        @JsonProperty("is_structural_query") Boolean isStructuralQuery
    ) {}
}

