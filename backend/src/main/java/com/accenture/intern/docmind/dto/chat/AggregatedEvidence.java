package com.accenture.intern.docmind.dto.chat;

import java.util.List;

public record AggregatedEvidence(
    String evidenceString,
    List<VisualEvidence> updatedVisuals
) {
}
