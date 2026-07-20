package com.accenture.intern.docmind.dto.chat;

import java.util.List;

public record VisualResult(
    List<VisualEvidence> visuals,
    int visualCandidates,
    String visualTier,
    long latency
) {}
