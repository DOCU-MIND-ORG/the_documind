package com.accenture.intern.docmind.dto.chat;

import org.springframework.ai.document.Document;
import java.util.List;

public record ContextResult(
        String systemPrompt,
        String prompt,
        List<Document> documents
) {}
