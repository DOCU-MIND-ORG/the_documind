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
public class BenchmarkQuery {
    private String id;
    private String category; // e.g. "Single-document", "Multi-document", "Comparison"
    private String query;
    
    // Assertions
    private String expectedTier; // "DIRECT", "DECOMPOSE", "ADAPTIVE"
    private String expectedStrategy; // "SINGLE_SOURCE", "MULTI_SOURCE", "CONCEPT_EXPANSION"
    private List<String> mustContainDocuments;
    private List<String> forbiddenDocuments;
    private Integer expectedDocumentsCount;
    private int minCitations;
    private java.util.Map<String, Integer> minimumChunksPerDocument;
    private List<String> allowedVisualSources;
    private String knownIssue;

    public java.util.Map<String, Integer> getMinimumChunksPerDocument() {
        return minimumChunksPerDocument;
    }

    public void setMinimumChunksPerDocument(java.util.Map<String, Integer> minimumChunksPerDocument) {
        this.minimumChunksPerDocument = minimumChunksPerDocument;
    }
}
