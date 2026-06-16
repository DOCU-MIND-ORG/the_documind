package com.accenture.intern.docmind.controllers;
import com.accenture.intern.docmind.service.DocumentParserService;
import com.accenture.intern.docmind.service.VectorStoreService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/ingest")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class IngestController {
    private final DocumentParserService parserService;
    private final VectorStoreService vectorStoreService;

    @PostMapping("/pdf")
    public ResponseEntity<?> ingestPdf(@RequestParam("file") MultipartFile file) {
        try {
            String text = parserService.parsePdf(file);
            vectorStoreService.ingest(text, "pdf", file.getOriginalFilename());
            return ResponseEntity.ok(Map.of("message", "PDF ingested successfully",
                    "chars", text.length()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/text")
    public ResponseEntity<?> ingestText(@RequestParam("file") MultipartFile file) {
        try {
            String text = parserService.parseTextFile(file);
            vectorStoreService.ingest(text, "markdown", file.getOriginalFilename());
            return ResponseEntity.ok(Map.of("message", "Text file ingested",
                    "chars", text.length()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }


    @PostMapping("/wikipedia")
    public ResponseEntity<?> ingestWikipedia(@RequestBody Map<String, String> body) {
        try {
            String url = body.get("url");
            // Extract title from URL like https://en.wikipedia.org/wiki/Java
            String title = url.contains("/wiki/")
                    ? url.substring(url.lastIndexOf("/wiki/") + 6)
                    : url;

            String text = parserService.fetchWikipedia(title);
            vectorStoreService.ingest(text, "wikipedia", url);
            return ResponseEntity.ok(Map.of("message", "Wikipedia article ingested",
                    "title", title));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // Should implement post mapping for the image
}
