package com.accenture.intern.docmind.dto.chat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SessionUploadState {

    /** Lifecycle for suggested-question generation, tracked separately from
     *  UploadState (ingestion) since question generation is an extra async step
     *  that only starts after ingestion reaches READY. */
    public enum SuggestedQuestionsStatus {
        NOT_STARTED,
        GENERATING,
        READY,
        FAILED
    }

    private List<EmbeddedDocument> embeddedDocuments;
    private UploadState state;

    /**
     * Raw text of every document ingested in this session so far (one entry per
     * upload, not per chunk). Kept around purely so SuggestedQuestionsService can
     * build starter questions from everything the user has uploaded in this
     * session combined, rather than just the most recent file.
     */
    private final List<String> ingestedDocumentTexts = new ArrayList<>();

    private volatile SuggestedQuestionsStatus questionsStatus = SuggestedQuestionsStatus.NOT_STARTED;
    private volatile List<String> suggestedQuestions = List.of();

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

    public synchronized void addIngestedDocumentText(String text) {
        if (text != null && !text.isBlank()) {
            ingestedDocumentTexts.add(text);
        }
    }

    public synchronized List<String> getIngestedDocumentTexts() {
        return Collections.unmodifiableList(new ArrayList<>(ingestedDocumentTexts));
    }

    public SuggestedQuestionsStatus getQuestionsStatus() {
        return questionsStatus;
    }

    public void setQuestionsStatus(SuggestedQuestionsStatus questionsStatus) {
        this.questionsStatus = questionsStatus;
    }

    public List<String> getSuggestedQuestions() {
        return suggestedQuestions;
    }

    public void setSuggestedQuestions(List<String> suggestedQuestions) {
        this.suggestedQuestions = suggestedQuestions == null ? List.of() : suggestedQuestions;
    }
}
