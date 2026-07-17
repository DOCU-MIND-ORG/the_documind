package com.accenture.intern.docmind.dto.chat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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

    public record DocumentPreparationStatus(Long jobId, String filename, UploadState state) {}

    private List<EmbeddedDocument> embeddedDocuments;
    private UploadState state;
    private final java.util.concurrent.ConcurrentHashMap<Long, DocumentPreparationStatus> documentStatuses = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Raw text of every document ingested in this session so far (one entry per
     * upload, not per chunk). Kept around purely so SuggestedQuestionsService can
     * build starter questions from everything the user has uploaded in this
     * session combined, rather than just the most recent file.
     */
    private final List<String> ingestedDocumentTexts = new ArrayList<>();

    /**
     * Set of active document filenames uploaded in this session.
     * Used by the Router to resolve references like "the first one" or "all three".
     */
    private final List<String> activeDocumentNames = new ArrayList<>();

    /**
     * Maps temporal/ordinal/topic aliases to their corresponding exact filename.
     * e.g., "latest" -> "roman_empire.pdf", "physics" -> "theory_of_relativity.pdf"
     */
    private final java.util.Map<String, String> aliases = new java.util.HashMap<>();

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

    public void updateDocumentStatus(Long jobId, String filename, UploadState status) {
        documentStatuses.put(jobId, new DocumentPreparationStatus(jobId, filename, status));
    }

    public void updateDocumentStateOnly(Long jobId, UploadState status) {
        DocumentPreparationStatus current = documentStatuses.get(jobId);
        if (current != null) {
            documentStatuses.put(jobId, new DocumentPreparationStatus(jobId, current.filename(), status));
        }
    }

    public java.util.Collection<DocumentPreparationStatus> getDocumentStatuses() {
        return documentStatuses.values();
    }

    public int getPendingIngestionsCount() {
        return (int) documentStatuses.values().stream()
                .filter(s -> s.state() == UploadState.QUEUED || s.state() == UploadState.INGESTING)
                .count();
    }

    public synchronized void addIngestedDocumentText(String text) {
        if (text != null && !text.isBlank()) {
            ingestedDocumentTexts.add(text);
        }
    }

    public synchronized List<String> getIngestedDocumentTexts() {
        return Collections.unmodifiableList(new ArrayList<>(ingestedDocumentTexts));
    }

    public synchronized void addActiveDocumentName(String filename) {
        if (filename != null && !filename.isBlank() && !activeDocumentNames.contains(filename)) {
            activeDocumentNames.add(filename);
        }
    }

    public synchronized List<String> getActiveDocumentNames() {
        return Collections.unmodifiableList(new ArrayList<>(activeDocumentNames));
    }

    public synchronized void addAlias(String alias, String filename) {
        if (alias != null && !alias.isBlank() && filename != null) {
            aliases.put(alias.toLowerCase(), filename);
        }
    }

    public synchronized java.util.Map<String, String> getAliases() {
        return Collections.unmodifiableMap(new java.util.HashMap<>(aliases));
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
