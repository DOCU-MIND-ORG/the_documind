package com.accenture.intern.docmind.dto.chat;

import lombok.Builder;

@Builder(toBuilder = true)
public record RetrievalTelemetry(
    int denseHits,
    int keywordHits,
    int rrfHits,
    int afterRerankHits,
    int afterMultiSignalHits,
    int contextExpandedHits,
    int finalHits,
    java.util.List<String> retrievalCandidateDocs,
    String visualTier,
    int visualCandidates,
    
    long plannerLatency,
    long denseLatency,
    long keywordLatency,
    long fusionLatency,
    long rerankLatency,
    long multiSignalLatency,
    long visualLatency,
    long totalRetrievalLatency,
    String plannerReason,
    String executionPath
) {
    public static RetrievalTelemetry empty() {
        return new RetrievalTelemetry(0, 0, 0, 0, 0, 0, 0, java.util.Collections.emptyList(), "NONE", 0, 0, 0, 0, 0, 0, 0, 0, 0, null, null);
    }
    public RetrievalTelemetry mergeWith(RetrievalTelemetry other) {
        if (other == null) return this;
        
        java.util.List<String> mergedDocs = new java.util.ArrayList<>();
        if (this.retrievalCandidateDocs != null) mergedDocs.addAll(this.retrievalCandidateDocs);
        if (other.retrievalCandidateDocs != null) {
            for (String doc : other.retrievalCandidateDocs) {
                if (!mergedDocs.contains(doc)) mergedDocs.add(doc);
            }
        }
        
        return new RetrievalTelemetry(
            this.denseHits + other.denseHits,
            this.keywordHits + other.keywordHits,
            this.rrfHits + other.rrfHits,
            this.afterRerankHits + other.afterRerankHits,
            this.afterMultiSignalHits + other.afterMultiSignalHits,
            this.contextExpandedHits + other.contextExpandedHits,
            0, // finalHits is intentionally NOT summed here — it must be set ONCE
            mergedDocs,
               // by the orchestrator after post-merge deduplication, using combined.size()
            "NONE".equals(this.visualTier) ? other.visualTier() : this.visualTier,
            this.visualCandidates + other.visualCandidates(),
            this.plannerLatency + other.plannerLatency(),
            this.denseLatency + other.denseLatency(),
            this.keywordLatency + other.keywordLatency(),
            this.fusionLatency + other.fusionLatency(),
            this.rerankLatency + other.rerankLatency(),
            this.multiSignalLatency + other.multiSignalLatency(),
            this.visualLatency + other.visualLatency(),
            this.totalRetrievalLatency + other.totalRetrievalLatency(),
            this.plannerReason == null ? other.plannerReason() : this.plannerReason,
            this.executionPath == null ? other.executionPath() : this.executionPath
        );
    }
}
