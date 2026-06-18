package com.accenture.intern.docmind.controller;

import com.accenture.intern.docmind.dto.attachment.AttachmentResponse;
import com.accenture.intern.docmind.service.AttachmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.security.Principal;
import java.util.List;

@RestController
@RequestMapping("/api/sessions/{sessionId}/attachments")
@RequiredArgsConstructor
public class AttachmentController {

    private final AttachmentService attachmentService;

    /**
     * POST /api/sessions/{sessionId}/attachments/upload
     *
     * Accepts multipart/form-data with a single field named "file".
     * Routes the file to the correct sub-folder automatically:
     *   PDF  → storage/pdfs/
     *   Image → storage/images/
     *   Text/MD → storage/texts/
     *   Everything else → storage/other/
     *
     * Persists an Attachment row in the DB and returns the saved metadata.
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<AttachmentResponse>> uploadFile(
            @PathVariable Long sessionId,
            @RequestPart("file") FilePart filePart,
            Principal principal
    ) {
        return attachmentService
                .uploadFile(sessionId, principal.getName(), filePart)
                .map(ResponseEntity::ok);
    }

    /**
     * GET /api/sessions/{sessionId}/attachments
     *
     * Returns all attachments uploaded across every message in this session.
     */
    @GetMapping
    public ResponseEntity<List<AttachmentResponse>> getSessionAttachments(
            @PathVariable Long sessionId,
            Principal principal
    ) {
        List<AttachmentResponse> attachments =
                attachmentService.getSessionAttachments(sessionId, principal.getName());
        return ResponseEntity.ok(attachments);
    }
}
