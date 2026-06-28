package com.accenture.intern.docmind.dto.session;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Polled by the frontend via GET /api/sessions/{id}/export-pdf/{jobId}.
 * Mirrors the same "submit job, poll for result" shape used by chat
 * (ChatJobResponse + stream) and suggested questions (SuggestedQuestionsResponse),
 * just without SSE since a PDF export is a single terminal result rather than a
 * token stream.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PdfExportStatusResponse {
    /** QUEUED | PROCESSING | READY | FAILED */
    private String status;

    /**
     * Relative path to GET /api/sessions/{sessionId}/export-pdf/{jobId}/download
     * — present only when status == READY. The PDF bytes are streamed
     * directly from this backend (no cloud storage involved); the frontend
     * just needs to navigate/fetch this path to trigger the browser download.
     */
    private String downloadPath;

    /** Suggested download filename for the frontend to use when triggering the download */
    private String fileName;

    /** Present only when status == FAILED */
    private String errorMessage;
}
