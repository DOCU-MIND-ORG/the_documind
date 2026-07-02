package com.accenture.intern.docmind.dto.chat;

public record VisualEvidence(
    String semanticId,
    String stableId,
    String imageUrl,
    String thumbnailUrl,
    String caption,
    double score,
    String sourceDocument
) {
}
