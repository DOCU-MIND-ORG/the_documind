package com.accenture.intern.docmind.dto.preference;

import lombok.Data;

@Data
public class UpdatePreferenceRequest {
    private String theme;
    private String language;
    private Boolean citationEnabled;
    private String responseStyle;
    private String modelName;
    private Double temperature;
}
