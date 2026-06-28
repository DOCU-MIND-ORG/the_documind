package com.accenture.intern.docmind.aiservices.understanding;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ComparisonTarget(
    String entity,
    @JsonProperty("optimized_query") String optimizedQuery
) {}

