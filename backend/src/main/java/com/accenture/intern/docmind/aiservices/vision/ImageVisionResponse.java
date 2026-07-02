package com.accenture.intern.docmind.aiservices.vision;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record ImageVisionResponse(
        @JsonProperty("suggested_filename") String suggestedFilename,
        @JsonProperty("asset_classification") String assetClassification,
        @JsonProperty("tags") List<String> tags,
        @JsonProperty("dense_description") String denseDescription
) {}

