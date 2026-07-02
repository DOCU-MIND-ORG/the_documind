package com.accenture.intern.docmind.dto.preference;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PreferenceResponse {
    private String theme;
    private String language;
    private Boolean citationEnabled;
    private String responseStyle;
    private String modelName;
    private Double temperature;
}
