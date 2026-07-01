package com.accenture.intern.docmind.controller;

import com.accenture.intern.docmind.dto.attachment.AttachmentDeleteResponse;
import com.accenture.intern.docmind.dto.attachment.AttachmentResponse;
import com.accenture.intern.docmind.service.AttachmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.security.Principal;
import java.util.List;

@RestController
@RequestMapping("/api/explore")
@RequiredArgsConstructor
public class ExploreController {

    private final AttachmentService attachmentService;

    /**
     * GET /api/explore/attachments
     *
     * Returns attachments belonging to the CURRENTLY LOGGED IN user only —
     * scoped by Attachment.userId. Includes PDFs, images, text files,
     * Wikipedia links, and any other type this user has personally uploaded.
     * Used by the "Explore" page.
     */
    @GetMapping("/attachments")
    public ResponseEntity<List<AttachmentResponse>> getAllAttachments(Principal principal) {
        return ResponseEntity.ok(attachmentService.getAllGlobalAttachments(principal.getName()));
    }

    /**
     * DELETE /api/explore/attachments/{attachmentId}
     *
     * Removes a file from the caller's Explore view. If the caller is the
     * only user who ever uploaded this exact content, the file is purged
     * everywhere (Cloudinary + document_chunks + Pinecone). If other users
     * also have it, only the caller's own reference is removed.
     */
    @DeleteMapping("/attachments/{attachmentId}")
    public Mono<ResponseEntity<AttachmentDeleteResponse>> deleteAttachment(
            @PathVariable Long attachmentId,
            Principal principal
    ) {
        return attachmentService.deleteExploreAttachment(attachmentId, principal.getName())
                .map(ResponseEntity::ok);
    }
}
