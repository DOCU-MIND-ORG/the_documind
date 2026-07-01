package com.accenture.intern.docmind.dto.job;

import com.accenture.intern.docmind.entity.SourceType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IngestionJobPayload {
    private Long jobId;
    private SourceType sourceType;
    private String sourceLocation;
    private Long sessionId;
    private Long userId;

    /**
     * The Cloudinary secure_url (or Wikipedia URL) this ingestion job's content
     * lives at. Threaded through so IngestionWorkerService can stamp every
     * resulting DocumentChunk.sourceUrl with the SAME url stored on the owning
     * Attachment row — that equality is what lets the Explore-page delete flow
     * (AttachmentService#deleteExploreAttachment) find every chunk/vector that
     * belongs to a given attachment.
     */
    private String sourceUrl;
}
