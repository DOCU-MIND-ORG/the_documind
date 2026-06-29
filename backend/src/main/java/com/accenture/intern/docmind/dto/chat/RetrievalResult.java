package com.accenture.intern.docmind.dto.chat;

import com.accenture.intern.docmind.dto.retrieval.RetrievalCandidate;
import com.accenture.intern.docmind.aiservices.understanding.Scope;
import java.util.List;

public record RetrievalResult(
    List<RetrievalCandidate> evidence,
    List<VisualEvidence> visuals,
    Scope requestedScope,
    Scope actualScope,
    boolean expandedScope,
    ExpansionReason reason
) {
    public RetrievalResult(List<RetrievalCandidate> evidence, List<VisualEvidence> visuals, Scope requestedScope) {
        this(evidence, visuals, requestedScope, requestedScope, false, ExpansionReason.NONE);
    }
}
