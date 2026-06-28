package com.accenture.intern.docmind.dto.retrieval;

import org.springframework.ai.document.Document;
import java.util.Collections;
import java.util.Set;

/**
 * An immutable carrier object representing a chunk of text as it moves through
 * the multi-stage ranking pipeline (Hybrid -> RRF -> CrossEncoder -> MultiSignalRanker).
 */
public record RetrievalCandidate(
        Document chunk,
        double semanticScore,
        double entityScore,
        double metadataScore,
        double sessionScore,
        double finalScore,
        Set<String> matchedEntities,
        RankingExplanation explanation
) {
    /**
     * Creates a new candidate starting its journey with a base score (e.g. from RRF).
     */
    public RetrievalCandidate(Document chunk, double baseScore) {
        this(chunk, baseScore, 1.0, 1.0, 1.0, baseScore, Collections.emptySet(), null);
    }

    /**
     * Returns a new instance with the semantic score updated (e.g. after Cross-Encoder).
     */
    public RetrievalCandidate withSemanticScore(double newSemanticScore) {
        return new RetrievalCandidate(
                this.chunk,
                newSemanticScore,
                this.entityScore,
                this.metadataScore,
                this.sessionScore,
                this.finalScore, // Final score isn't computed until MultiSignalRanker
                this.matchedEntities,
                this.explanation
        );
    }

    /**
     * Returns a new instance with the multi-signal ranking scores populated.
     */
    public RetrievalCandidate withRankingResults(
            double newEntityScore,
            double newMetadataScore,
            double newSessionScore,
            double newFinalScore,
            Set<String> newMatchedEntities,
            RankingExplanation newExplanation) {
        return new RetrievalCandidate(
                this.chunk,
                this.semanticScore,
                newEntityScore,
                newMetadataScore,
                newSessionScore,
                newFinalScore,
                newMatchedEntities,
                newExplanation
        );
    }
}
