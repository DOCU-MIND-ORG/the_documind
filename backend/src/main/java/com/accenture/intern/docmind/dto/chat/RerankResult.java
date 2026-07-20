package com.accenture.intern.docmind.dto.chat;

import com.accenture.intern.docmind.dto.retrieval.RetrievalCandidate;
import java.util.List;

public record RerankResult(
    List<RetrievalCandidate> candidates,
    int afterRerankHits,
    long latency
) {}
