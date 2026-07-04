package com.accenture.intern.docmind.dto.job;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MemoryJobPayload {
    private Long sessionId;
    private Integer startTurn;
    private Integer endTurn;
    private String episodeContent; // Or we can fetch it dynamically based on turns
}
