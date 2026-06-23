package com.accenture.intern.docmind.dto.preference;

import lombok.Data;

@Data
public class UpdateModelRequest {
    private String modelName;
    private String theme;
    private String responseStyle;
    private String language;
}