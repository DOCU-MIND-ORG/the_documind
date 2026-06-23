package com.accenture.intern.docmind.dto.chat;

import java.util.List;

public class SessionUploadState {
    private List<EmbeddedDocument> embeddedDocuments;
    private UploadState state;

    public SessionUploadState() {
        this.state = UploadState.UPLOADING;
    }

    public List<EmbeddedDocument> getEmbeddedDocuments() {
        return embeddedDocuments;
    }

    public void setEmbeddedDocuments(List<EmbeddedDocument> embeddedDocuments) {
        this.embeddedDocuments = embeddedDocuments;
    }

    public UploadState getState() {
        return state;
    }

    public void setState(UploadState state) {
        this.state = state;
    }
}
