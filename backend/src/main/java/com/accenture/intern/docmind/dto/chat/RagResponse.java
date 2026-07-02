package com.accenture.intern.docmind.dto.chat;

import java.util.List;
import java.util.Map;

public record RagResponse(
        String answer,
        List<Map<String, Object>> citations,
        boolean foundInDocuments
) {}
