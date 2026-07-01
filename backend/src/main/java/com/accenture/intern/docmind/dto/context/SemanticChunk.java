package com.accenture.intern.docmind.dto.context;

import lombok.Builder;
import java.util.Map;

@Builder
public record SemanticChunk(
        String semanticId,
        SourceType sourceType,
        ChunkType type,
        String text,
        String sectionPath,
        String page,
        int order,
        Map<String, Object> metadata
) {
}
