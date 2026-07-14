package com.accenture.intern.docmind.dto.session;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

import com.accenture.intern.docmind.entity.MessageRole;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageResponse {
    private String id;
    private MessageRole role;
    private String text;
    private String status;
    private LocalDateTime createdAt;
    private Object citations;
    private Object visuals;
}
