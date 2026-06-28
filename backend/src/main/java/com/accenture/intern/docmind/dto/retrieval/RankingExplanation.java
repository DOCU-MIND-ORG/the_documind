package com.accenture.intern.docmind.dto.retrieval;

/**
 * Provides a structured breakdown of why a specific chunk received its final ranking score.
 * Used for debugging, tracing, and metric evaluation.
 */
public record RankingExplanation(
        double semanticScore,
        double entityBoost,
        double metadataBoost,
        double sessionBoost,
        double finalScore,
        String reasoning
) {
}
