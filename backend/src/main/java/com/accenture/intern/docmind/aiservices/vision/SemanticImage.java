package com.accenture.intern.docmind.aiservices.vision;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public record SemanticImage(
        String summary,
        List<String> keywords,
        List<String> entities,
        List<String> topics,
        List<String> technologies,
        List<String> objects,
        List<String> relationships,
        String ocr,
        String imageType,
        String context
) {
    /**
     * Concatenates the structured fields into a dense, keyword-rich block 
     * optimized for semantic and keyword retrieval.
     */
    public String toDenseEmbeddingText() {
        StringBuilder sb = new StringBuilder();
        
        if (imageType != null && !imageType.isBlank()) {
            sb.append("[Image Type: ").append(imageType).append("]\n");
        }
        if (summary != null && !summary.isBlank()) {
            sb.append("[Summary: ").append(summary).append("]\n");
        }
        if (objects != null && !objects.isEmpty()) {
            sb.append("[Objects: ").append(String.join(", ", objects)).append("]\n");
        }
        if (entities != null && !entities.isEmpty()) {
            sb.append("[Entities: ").append(String.join(", ", entities)).append("]\n");
        }
        if (topics != null && !topics.isEmpty()) {
            sb.append("[Topics: ").append(String.join(", ", topics)).append("]\n");
        }
        if (technologies != null && !technologies.isEmpty()) {
            sb.append("[Technologies: ").append(String.join(", ", technologies)).append("]\n");
        }
        if (relationships != null && !relationships.isEmpty()) {
            sb.append("[Relationships: ").append(String.join("; ", relationships)).append("]\n");
        }
        if (ocr != null && !ocr.isBlank()) {
            sb.append("[OCR Text: ").append(ocr).append("]\n");
        }
        if (keywords != null && !keywords.isEmpty()) {
            sb.append("[Keywords: ").append(String.join(", ", keywords)).append("]\n");
        }
        
        return sb.toString().trim();
    }
}
