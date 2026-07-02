package com.accenture.intern.docmind.dto.session;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Returned immediately from POST /api/sessions/{id}/export-pdf — the job has
 * only been enqueued, not completed yet. The frontend polls
 * GET /api/sessions/{id}/export-pdf/{jobId} with this jobId to find out when
 * the PDF is ready.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PdfExportJobResponse {
    private String jobId;
    private String status; // QUEUED | PROCESSING | READY | FAILED
}
