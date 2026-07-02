package com.accenture.intern.docmind.dto.session;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Payload pushed onto the {@code pdf_export_jobs} Redis stream by
 * {@link com.accenture.intern.docmind.service.SessionService#requestPdfExport}.
 * Picked up by {@link com.accenture.intern.docmind.service.PdfExportWorkerService},
 * which runs on a worker thread (never the request thread) since PDF generation
 * involves an extra LLM call (for the summary) plus PDF rendering and a
 * Cloudinary upload — all too slow/heavy to do inline in an HTTP handler.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PdfExportJob implements Serializable {
    private String jobId;
    private Long sessionId;
    private String userEmail;
    private Long timestamp;
}
