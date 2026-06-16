package com.accenture.intern.docmind.dto.session;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionResponse {
    private Long sessionId;
    private String title;
    private Boolean archived;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
