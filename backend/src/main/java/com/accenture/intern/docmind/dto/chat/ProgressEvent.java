package com.accenture.intern.docmind.dto.chat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProgressEvent(
    UUID id,
    ProgressStage stage,
    ProgressStatus status,
    String message,
    Integer iteration,
    Integer totalIterations,
    Map<String, Object> metadata,
    Instant timestamp
) {
    public ProgressEvent(ProgressStage stage, ProgressStatus status, String message, Integer iteration, Integer totalIterations, Map<String, Object> metadata) {
        this(UUID.randomUUID(), stage, status, message, iteration, totalIterations, metadata, Instant.now());
    }

    public String toJson(ObjectMapper mapper) {
        try {
            return mapper.writeValueAsString(this);
        } catch (Exception e) {
            return "{}";
        }
    }
}
