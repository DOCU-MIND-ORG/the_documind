package com.accenture.intern.docmind.dto.chat;

import com.accenture.intern.docmind.dto.retrieval.RetrievalCandidate;
import java.util.List;

public record ContextResult(
        String systemPrompt,
        String prompt,
        List<RetrievalCandidate> documents, // The merged list for backward compatibility with downstream
        List<RetrievalCandidate> primaryChunks,
        List<RetrievalCandidate> sessionChunks,
        List<RetrievalCandidate> memoryChunks,
        List<VisualEvidence> visuals,
        RetrievalTrace trace,
        Double topScore
) {
    public ContextResult(String systemPrompt, String prompt, List<RetrievalCandidate> documents, Double topScore) {
        this(systemPrompt, prompt, documents, documents, java.util.Collections.emptyList(), java.util.Collections.emptyList(), java.util.Collections.emptyList(), new RetrievalTrace(), topScore);
    }
}
