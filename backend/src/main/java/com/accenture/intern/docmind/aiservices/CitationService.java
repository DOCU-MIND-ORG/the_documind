package com.accenture.intern.docmind.aiservices;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class CitationService {

    public List<Map<String, Object>> extractCitations(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }

        return documents.stream()
                .map(doc -> {
                    // Extract score from metadata, formatting to 2 decimal places if present
                    Object scoreObj = doc.getMetadata().get("score");
                    Double score = null;

                    if (scoreObj instanceof Number) {
                        score = ((Number) scoreObj).doubleValue();
                    } else if (scoreObj instanceof String) {
                        try {
                            score = Double.parseDouble((String) scoreObj);
                        } catch (NumberFormatException e) {
                            // ignore
                        }
                    }

                    return Map.<String, Object>of(
                            "sourceName", doc.getMetadata().getOrDefault("sourceName", "unknown"),
                            "sourceType", doc.getMetadata().getOrDefault("sourceType", ""),
                            "chunkIndex", doc.getMetadata().getOrDefault("chunkIndex", 0),
                            "excerpt", doc.getText().substring(0, Math.min(200, doc.getText().length())) + "…",
                            "fullExcerpt", doc.getText(),
                            "score", score != null ? score : 0.0d,
                            "documentId", doc.getMetadata().getOrDefault("documentId", ""));
                })
                .collect(Collectors.toList());
    }
}
