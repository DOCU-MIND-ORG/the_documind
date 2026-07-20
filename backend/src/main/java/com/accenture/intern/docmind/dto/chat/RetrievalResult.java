package com.accenture.intern.docmind.dto.chat;

import com.accenture.intern.docmind.dto.retrieval.RetrievalCandidate;
import com.accenture.intern.docmind.aiservices.understanding.Scope;
import java.util.List;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalResult {
    private List<RetrievalCandidate> evidence;
    private List<VisualEvidence> visuals;
    private Scope requestedScope;
    private Scope actualScope;
    private boolean expandedScope;
    private ExpansionReason reason;
    private RetrievalTelemetry telemetry;

    public RetrievalResult(List<RetrievalCandidate> evidence, List<VisualEvidence> visuals, Scope requestedScope) {
        this(evidence, visuals, requestedScope, requestedScope, false, ExpansionReason.NONE, RetrievalTelemetry.empty());
    }
    
    public RetrievalResult(List<RetrievalCandidate> evidence, List<VisualEvidence> visuals, Scope requestedScope, Scope actualScope, boolean expandedScope, ExpansionReason reason) {
        this(evidence, visuals, requestedScope, actualScope, expandedScope, reason, RetrievalTelemetry.empty());
    }
}
