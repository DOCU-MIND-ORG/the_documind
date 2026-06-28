package com.accenture.intern.docmind.aiservices.understanding;

/**
 * Represents a specific entity resolved by Query Understanding,
 * along with the LLM's confidence that the query actually refers to this entity.
 */
import com.fasterxml.jackson.annotation.JsonProperty;

public record EntityResolution(
        @JsonProperty("name") String canonicalEntity,
        @JsonProperty("confidence") double resolutionConfidence
) {
}
