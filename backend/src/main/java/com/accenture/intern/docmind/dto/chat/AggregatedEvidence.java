package com.accenture.intern.docmind.dto.chat;

import com.accenture.intern.docmind.dto.retrieval.RetrievalCandidate;
import java.util.List;

public record AggregatedEvidence(
    String evidenceString,
    List<VisualEvidence> updatedVisuals,
    List<RetrievalCandidate> orderedCandidates
) {
}
