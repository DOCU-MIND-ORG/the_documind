package com.accenture.intern.docmind.dto.context;

import java.util.List;
import java.util.Map;

public record SessionContext(
    Long sessionId,
    List<DocumentReference> uploadedDocuments,
    String activeDocument,
    Map<String, String> aliases
) {
}
