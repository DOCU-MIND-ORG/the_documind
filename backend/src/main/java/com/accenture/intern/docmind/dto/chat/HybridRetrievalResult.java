package com.accenture.intern.docmind.dto.chat;

import com.accenture.intern.docmind.dto.retrieval.RetrievalCandidate;
import java.util.List;

public record HybridRetrievalResult(
    List<RetrievalCandidate> candidates,
    int denseHits,
    int keywordHits,
    int rrfHits,
    long denseLatency,
    long keywordLatency,
    long fusionLatency
) {}
