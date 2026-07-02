package com.accenture.intern.docmind.aiservices.retrieval;

public record RetrievalQuality(
        double topScore,
        double averageTop5,
        int retrievedChunks,
        int uniqueDocuments
) {
    public double getConfidence() {
        return (0.6 * topScore) + (0.4 * averageTop5);
    }
}

