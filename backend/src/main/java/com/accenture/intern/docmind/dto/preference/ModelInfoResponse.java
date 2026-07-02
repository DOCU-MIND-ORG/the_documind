package com.accenture.intern.docmind.dto.preference;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelInfoResponse {
    private String id;
    private String name;
    private String description;
    private Boolean isNew;
}