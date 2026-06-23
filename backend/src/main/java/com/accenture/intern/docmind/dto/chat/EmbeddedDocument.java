package com.accenture.intern.docmind.dto.chat;

import org.springframework.ai.document.Document;

public class EmbeddedDocument {
    private final Document document;
    private final float[] embedding;

    public EmbeddedDocument(Document document, float[] embedding) {
        this.document = document;
        this.embedding = embedding;
    }

    public Document getDocument() {
        return document;
    }

    public float[] getEmbedding() {
        return embedding;
    }
}
