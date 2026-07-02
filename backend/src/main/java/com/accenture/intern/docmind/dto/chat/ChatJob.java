package com.accenture.intern.docmind.dto.chat;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatJob implements Serializable {
    private Long messageId;
    private Long sessionId;
    private String query;
    private String model;
    private Long timestamp;
}
