package com.accenture.intern.docmind.aiservices.retrieval;

import com.accenture.intern.docmind.aiservices.understanding.EntityResolution;
import com.accenture.intern.docmind.aiservices.understanding.RetrievalPlan;
import com.accenture.intern.docmind.dto.retrieval.RankingExplanation;
import com.accenture.intern.docmind.dto.retrieval.RetrievalCandidate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class MultiSignalRanker {

    private static final double ENTITY_MATCH_BOOST = 1.2;
    private static final double ENTITY_MISMATCH_PENALTY = 0.8;
    private static final double TARGET_DOC_BOOST = 1.15;
    private static final double SESSION_BOOST = 1.1;

    /**
     * Applies business logic signals (Entities, Metadata, Session) on top of the
     * semantic score from the Cross-Encoder.
     */
    public List<RetrievalCandidate> rank(List<RetrievalCandidate> candidates, RetrievalPlan plan, List<EntityResolution> requestedEntities, Long currentSessionId) {
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }

        List<RetrievalCandidate> rankedCandidates = new ArrayList<>();
        if (requestedEntities == null) {
            requestedEntities = List.of();
        }
        List<String> targetDocuments = plan.targetDocuments() != null ? plan.targetDocuments() : List.of();

        for (RetrievalCandidate candidate : candidates) {
            Map<String, Object> metadata = candidate.chunk().getMetadata();
            String chunkSourceName = (String) metadata.getOrDefault("sourceName", "");
            Long chunkSessionId = getSessionIdFromMetadata(metadata);

            double entityBoost = calculateEntityCompatibility(requestedEntities, chunkSourceName);
            double metadataBoost = calculateMetadataCompatibility(targetDocuments, chunkSourceName);
            double sessionBoost = calculateSessionCompatibility(currentSessionId, chunkSessionId);

            double compatibility = entityBoost * metadataBoost * sessionBoost;
            double finalScore = candidate.semanticScore() * compatibility;

            Set<String> matchedEntities = new HashSet<>();
            if (entityBoost > 1.0) {
                for (EntityResolution er : requestedEntities) {
                    if (chunkSourceName.toLowerCase().contains(er.canonicalEntity().toLowerCase())) {
                        matchedEntities.add(er.canonicalEntity());
                    }
                }
            }

            String reasoning = String.format(
                    "Semantic: %.3f | EntityBoost: %.2f | MetadataBoost: %.2f | SessionBoost: %.2f | Final: %.3f",
                    candidate.semanticScore(), entityBoost, metadataBoost, sessionBoost, finalScore);

            RankingExplanation explanation = new RankingExplanation(
                    candidate.semanticScore(),
                    entityBoost,
                    metadataBoost,
                    sessionBoost,
                    finalScore,
                    reasoning
            );

            RetrievalCandidate updatedCandidate = candidate.withRankingResults(
                    entityBoost,
                    metadataBoost,
                    sessionBoost,
                    finalScore,
                    matchedEntities,
                    explanation
            );

            rankedCandidates.add(updatedCandidate);
        }

        // Sort descending by finalScore
        rankedCandidates.sort((a, b) -> Double.compare(b.finalScore(), a.finalScore()));

        if (!rankedCandidates.isEmpty()) {
            double bestScore = rankedCandidates.get(0).finalScore();
            double threshold = Math.max(bestScore * 0.8, 0.1);
            
            // Filter out highly irrelevant candidates to avoid polluting the LLM context
            rankedCandidates = rankedCandidates.stream()
                    .filter(c -> {
                        String type = (String) c.chunk().getMetadata().get("type");
                        boolean isImage = "IMAGE".equals(type) || "PDF_IMAGE".equals(type);
                        // Images have short captions, so they score lower semantically. Give them a lower threshold.
                        return c.finalScore() >= threshold || (isImage && c.finalScore() >= 0.01);
                    })
                    .collect(Collectors.toList());
        }

        // Log top results for debugging
        log.info("MultiSignalRanker completed for {} candidates.", rankedCandidates.size());
        for (int i = 0; i < Math.min(3, rankedCandidates.size()); i++) {
            RetrievalCandidate c = rankedCandidates.get(i);
            log.info("Rank {}: Source={} | {}", i + 1, c.chunk().getMetadata().get("sourceName"), c.explanation().reasoning());
        }

        return rankedCandidates;
    }

    private double calculateEntityCompatibility(List<EntityResolution> requestedEntities, String chunkSourceName) {
        if (requestedEntities.isEmpty() || chunkSourceName.isEmpty()) {
            return 1.0; // Neutral if no entities requested or unknown source
        }

        boolean matchedAny = false;
        String lowerSource = chunkSourceName.toLowerCase();
        
        for (EntityResolution er : requestedEntities) {
            if (er.canonicalEntity() == null || er.canonicalEntity().isBlank()) continue;
            // chunkSourceName is always stored normalized (no extension, no
            // underscores/hyphens - see FilenameNormalizer), but canonicalEntity
            // may still be the raw filename the LLM resolved the reference to
            // (e.g. "Jio_5G_Rollout_Project.pdf"). Normalize before comparing so
            // a real match isn't missed just because of formatting differences;
            // normalizing a non-filename entity (e.g. "flipkart project") is a
            // harmless no-op since it's already lowercase with no separators.
            String normalizedEntity = com.accenture.intern.docmind.util.FilenameNormalizer.normalize(er.canonicalEntity());
            if (!normalizedEntity.isBlank() && lowerSource.contains(normalizedEntity)) {
                matchedAny = true;
                break;
            }
        }

        return matchedAny ? ENTITY_MATCH_BOOST : ENTITY_MISMATCH_PENALTY;
    }

    private double calculateMetadataCompatibility(List<String> targetDocuments, String chunkSourceName) {
        if (targetDocuments.isEmpty() || chunkSourceName.isEmpty()) {
            return 1.0;
        }

        String lowerSource = chunkSourceName.toLowerCase();
        for (String target : targetDocuments) {
            if (target == null || target.isBlank()) continue;
            // Same normalization concern as calculateEntityCompatibility above:
            // targetDocuments are "resolved exact filenames" per the router
            // prompt, while chunkSourceName is always the normalized form.
            String normalizedTarget = com.accenture.intern.docmind.util.FilenameNormalizer.normalize(target);
            if (!normalizedTarget.isBlank() && lowerSource.contains(normalizedTarget)) {
                return TARGET_DOC_BOOST;
            }
        }
        return 1.0;
    }

    private double calculateSessionCompatibility(Long currentSessionId, Long chunkSessionId) {
        if (currentSessionId != null && currentSessionId.equals(chunkSessionId)) {
            return SESSION_BOOST;
        }
        return 1.0;
    }

    private Long getSessionIdFromMetadata(Map<String, Object> metadata) {
        Object val = metadata.get("sessionId");
        if (val instanceof Number n) return n.longValue();
        if (val instanceof String s) {
            try { return Long.parseLong(s); } catch (NumberFormatException ignored) {}
        }
        return null;
    }
}
