package com.accenture.intern.docmind.dto.job;

import com.accenture.intern.docmind.entity.SourceType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IngestionJobPayload {
    private Long jobId;
    private SourceType sourceType;
    private String sourceLocation;
    private Long sessionId;
    private Long userId;
}
