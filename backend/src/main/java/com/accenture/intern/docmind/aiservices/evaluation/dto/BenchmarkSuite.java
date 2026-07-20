package com.accenture.intern.docmind.aiservices.evaluation.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BenchmarkSuite {
    private String version;
    private String corpusVersion;
    private String description;
    private List<BenchmarkQuery> queries;
}
