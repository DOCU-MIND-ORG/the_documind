package com.accenture.intern.docmind.aiservices;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
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
                    // Extract score from metadata
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

                    Object imageUrlObj = doc.getMetadata().get("imageUrl");
                    String imageUrl = imageUrlObj instanceof String s && !s.isBlank() ? s : null;
                    boolean isImage = imageUrl != null;

                    Map<String, Object> citation = new LinkedHashMap<>();
                    citation.put("sourceName", doc.getMetadata().getOrDefault("sourceName", "unknown"));
                    citation.put("sourceType", doc.getMetadata().getOrDefault("sourceType", ""));
                    citation.put("chunkIndex", doc.getMetadata().getOrDefault("chunkIndex", 0));
                    citation.put("excerpt", doc.getText().substring(0, Math.min(200, doc.getText().length())) + "…");
                    citation.put("fullExcerpt", doc.getText());
                    citation.put("score", score != null ? score : 0.0d);
                    citation.put("documentId", doc.getMetadata().getOrDefault("documentId", ""));
                    citation.put("isImage", isImage);
                    citation.put("imageUrl", imageUrl);
                    return citation;
                })
                .collect(Collectors.toList());
    }
}
