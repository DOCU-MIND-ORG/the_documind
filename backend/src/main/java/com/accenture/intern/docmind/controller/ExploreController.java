package com.accenture.intern.docmind.controller;

import com.accenture.intern.docmind.dto.attachment.AttachmentResponse;
import com.accenture.intern.docmind.service.AttachmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/explore")
@RequiredArgsConstructor
public class ExploreController {

    private final AttachmentService attachmentService;

    /**
     * GET /api/explore/attachments
     *
     * Returns ALL attachments uploaded across every session of every user —
     * including PDFs, images, text files, Wikipedia links, and any other type.
     * Used by the "Explore" page which shows the global knowledge base.
     */
    @GetMapping("/attachments")
    public ResponseEntity<List<AttachmentResponse>> getAllAttachments() {
        return ResponseEntity.ok(attachmentService.getAllGlobalAttachments());
    }
}
