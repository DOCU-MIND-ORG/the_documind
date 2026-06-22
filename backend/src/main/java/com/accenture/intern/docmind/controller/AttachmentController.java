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
                .flatMap(result -> result.ingestionMono()
                        // Wait for chunking/embedding/session-cache population to
                        // actually finish before the HTTP response goes back. If we
                        // just subscribe()'d and returned immediately, a question sent
                        // right after the upload (e.g. upload + "explain this doc" in
                        // one combined send) could race ahead of ingestion and find
                        // nothing in the session cache yet — see ContextBuilderService.
                        .onErrorResume(e -> Mono.empty())
                        .thenReturn(ResponseEntity.ok(result.response())));
    }

    /**
     * POST /api/sessions/{sessionId}/attachments/wikipedia
     *
     * Accepts a JSON payload with a "url" field containing a Wikipedia URL.
     * Fetches the page content and stores it as an attachment.
     */
    @PostMapping(value = "/wikipedia", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<AttachmentResponse>> uploadWikipedia(
            @PathVariable Long sessionId,
            @RequestBody java.util.Map<String, String> payload,
            Principal principal
    ) {
        String url = payload.get("url");
        if (url == null || url.isBlank()) {
            return Mono.just(ResponseEntity.badRequest().build());
        }

        return attachmentService
                .uploadWikipedia(sessionId, principal.getName(), url)
                .flatMap(result -> result.ingestionMono()
                        .onErrorResume(e -> Mono.empty())
                        .thenReturn(ResponseEntity.ok(result.response())));
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
