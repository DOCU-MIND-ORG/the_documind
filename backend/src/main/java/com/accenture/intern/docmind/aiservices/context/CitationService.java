package com.accenture.intern.docmind.aiservices.context;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import com.accenture.intern.docmind.dto.retrieval.RetrievalCandidate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class CitationService {

    /**
     * A retrieved candidate's source is only worth showing as a citation if
     * it's reasonably competitive with the best evidence found, OR came from
     * an exact whole-document match (always trustworthy, base score 1.0).
     * Without this, a weak adaptive-RAG fallback that wandered through several
     * unrelated company documents (each contributing a couple of low-scoring
     * chunks) ends up listing every one of those documents as a "citation"
     * alongside the one source the answer is actually grounded in.
     */
    private static final double RELEVANT_SOURCE_SCORE_RATIO = 0.3;

    public List<Map<String, Object>> extractCitations(List<RetrievalCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            RetrievalCandidate cand = candidates.get(i);
            Double score = cand.finalScore();
            if (score == 0.0) score = cand.semanticScore();

            Object imageUrlObj = cand.chunk().getMetadata().get("imageUrl");
            String imageUrl = imageUrlObj instanceof String s && !s.isBlank() ? s : null;
            boolean isImage = imageUrl != null;

            if (score != null) {
                score = Math.round(score * 1000.0) / 1000.0;
            }

            Map<String, Object> citation = new LinkedHashMap<>();
            citation.put("citationNumber", i + 1); // 1-based to match LLM output
            citation.put("sourceName", cand.chunk().getMetadata().getOrDefault("sourceName", "unknown"));
            citation.put("sourceType", cand.chunk().getMetadata().getOrDefault("sourceType", ""));
            citation.put("chunkIndex", cand.chunk().getMetadata().getOrDefault("chunkIndex", 0));
            citation.put("excerpt", cand.chunk().getText().substring(0, Math.min(200, cand.chunk().getText().length())) + "…");
            citation.put("fullExcerpt", cand.chunk().getText());
            citation.put("score", score);
            citation.put("documentId", cand.chunk().getMetadata().getOrDefault("documentId", ""));
            citation.put("isImage", isImage);
            citation.put("imageUrl", imageUrl);

            Object sourceUrlObj = cand.chunk().getMetadata().get("sourceUrl");
            String sourceUrl = sourceUrlObj instanceof String s && !s.isBlank() ? s : null;
            citation.put("url", sourceUrl);
            
            if (cand.explanation() != null) {
                citation.put("rankingExplanation", cand.explanation().reasoning());
            }
            
            if (cand.chunk().getMetadata().containsKey("page")) {
                citation.put("page", cand.chunk().getMetadata().get("page"));
            }
            if (cand.chunk().getMetadata().containsKey("boundingBoxes")) {
                citation.put("boundingBoxes", cand.chunk().getMetadata().get("boundingBoxes"));
            }
            if (cand.chunk().getMetadata().containsKey("sectionPath")) {
                citation.put("sectionPath", cand.chunk().getMetadata().get("sectionPath"));
            }
            
            result.add(citation);
        }
        return result;
    }

    /**
     * Groups candidates by sourceName and keeps only the sources whose best
     * candidate score is competitive with the overall best score (within
     * RELEVANT_SOURCE_SCORE_RATIO), or that contain at least one exact
     * whole-document match. The single best source is always kept, even if
     * every score in the batch is uniformly low (e.g. a weak adaptive
     * fallback) — the point isn't to demand a high absolute score, only to
     * stop unrelated also-ran sources from riding along as "citations".
     */
    private List<RetrievalCandidate> filterToRelevantSources(List<RetrievalCandidate> candidates) {
        Map<Object, List<RetrievalCandidate>> bySource = new LinkedHashMap<>();
        for (RetrievalCandidate cand : candidates) {
            Object sourceName = cand.chunk().getMetadata().getOrDefault("sourceName", "unknown");
            bySource.computeIfAbsent(sourceName, k -> new java.util.ArrayList<>()).add(cand);
        }

        if (bySource.size() <= 1) {
            return candidates;
        }

        double topScore = 0.0;
        for (List<RetrievalCandidate> group : bySource.values()) {
            for (RetrievalCandidate cand : group) {
                double score = effectiveScore(cand);
                if (score > topScore) topScore = score;
            }
        }

        List<RetrievalCandidate> filtered = new java.util.ArrayList<>();
        for (List<RetrievalCandidate> group : bySource.values()) {
            boolean hasWholeDocumentMatch = group.stream()
                    .anyMatch(cand -> Boolean.TRUE.equals(cand.chunk().getMetadata().get("wholeDocumentMatch")));
            double groupBestScore = group.stream().mapToDouble(this::effectiveScore).max().orElse(0.0);

            boolean isRelevant = hasWholeDocumentMatch
                    || topScore <= 0.0
                    || groupBestScore >= topScore * RELEVANT_SOURCE_SCORE_RATIO;

            if (isRelevant) {
                filtered.addAll(group);
            }
        }

        return filtered.isEmpty() ? candidates : filtered;
    }

    private double effectiveScore(RetrievalCandidate cand) {
        double score = cand.finalScore();
        return score == 0.0 ? cand.semanticScore() : score;
    }
}

