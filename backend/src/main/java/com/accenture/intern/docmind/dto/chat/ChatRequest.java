package com.accenture.intern.docmind.dto.chat;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequest {
    private String message;
    private String model;
    private java.util.List<String> inflightJobIds;
}
