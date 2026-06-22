package com.accenture.intern.docmind.service;

import com.accenture.intern.docmind.dto.attachment.AttachmentResponse;
import com.accenture.intern.docmind.dto.attachment.AttachmentUploadResult;
import com.accenture.intern.docmind.entity.*;
import com.accenture.intern.docmind.repository.AttachmentRepository;
import com.accenture.intern.docmind.repository.MessageRepository;
import com.accenture.intern.docmind.repository.SessionRepository;
import lombok.extern.slf4j.Slf4j;
import com.accenture.intern.docmind.aiservices.DocumentParserService;
import com.accenture.intern.docmind.aiservices.EmbeddingService;
import com.accenture.intern.docmind.aiservices.ImageVisionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@Transactional
public class AttachmentService {

    private final AttachmentRepository attachmentRepository;
    private final MessageRepository messageRepository;
    private final SessionRepository sessionRepository;
    private final DocumentParserService parserService;
    private final EmbeddingService embeddingService;
    private final ImageVisionService imageVisionService;

    /** Root storage dir — always resolved to absolute path at startup */
    @Value("${app.storage.root:storage}")
    private String storageRoot;

    private Path absoluteStorageRoot;

    public AttachmentService(AttachmentRepository attachmentRepository,
                             MessageRepository messageRepository,
                             SessionRepository sessionRepository,
                             DocumentParserService parserService,
                             EmbeddingService embeddingService,
                             ImageVisionService imageVisionService) {
        this.attachmentRepository = attachmentRepository;
        this.messageRepository = messageRepository;
        this.sessionRepository = sessionRepository;
        this.parserService = parserService;
        this.embeddingService = embeddingService;
        this.imageVisionService = imageVisionService;
    }

    @jakarta.annotation.PostConstruct
    public void init() throws IOException {
        // Resolve relative path (e.g. "storage") to absolute based on CWD (backend/)
        absoluteStorageRoot = Paths.get(storageRoot).toAbsolutePath().normalize();
        Files.createDirectories(absoluteStorageRoot);
        System.out.println("📁 Storage root: " + absoluteStorageRoot);
    }

    // ── Upload ────────────────────────────────────────────────────────────────

    /**
     * Saves the file to the correct sub-folder under storageRoot,
     * then persists an Attachment row linked to a system Message in the given session.
     */
    public Mono<AttachmentUploadResult> uploadFile(Long sessionId, String userEmail, FilePart filePart) {
        return Mono.fromCallable(() -> {
            // 1. Verify session belongs to caller
            Session session = sessionRepository.findById(sessionId)
                    .orElseThrow(() -> new RuntimeException("Session not found"));

            if (!session.getUser().getEmail().equals(userEmail)) {
                throw new RuntimeException("Access denied");
            }

            // 2. Determine type + subfolder from content-type
            String contentType = filePart.headers().getContentType() != null
                    ? filePart.headers().getContentType().toString()
                    : "application/octet-stream";

            AttachmentType type = resolveType(contentType);
            String subFolder = resolveFolder(type);

            // 3. Build destination path using the resolved absolute root
            Path dir = absoluteStorageRoot.resolve(subFolder);
            Files.createDirectories(dir);

            String originalName = filePart.filename();
            String storedName = UUID.randomUUID() + "_" + originalName;
            Path dest = dir.resolve(storedName);

            // 4. Write file to disk (blocking, hence fromCallable + boundedElastic)
            filePart.transferTo(dest).block();

            // Store a relative path so it works even if the server moves
            String storagePath = subFolder + "/" + storedName;
            // The url is the public serving path: GET /files/{storagePath}
            String publicUrl = "/files/" + storagePath;

            List<Mono<Void>> ingestionMonos = new ArrayList<>();
            try {
                if (type == AttachmentType.PDF) {
                    // Extracts page text, AND separately every embedded chart/graph/
                    // image (described via Gemini Vision) — see DocumentParserService.
                    DocumentParserService.PdfParseResult parsed = parserService.parsePdfWithImages(dest);

                    if (parsed.text() != null && !parsed.text().isBlank()) {
                        ingestionMonos.add(
                                embeddingService.processAndIngest(parsed.text(), type.name(), originalName, sessionId));
                    }

                    int imgIndex = 0;
                    for (DocumentParserService.ExtractedImage img : parsed.images()) {
                        imgIndex++;
                        String extractedImageUrl = saveExtractedPdfImage(img, originalName, imgIndex);
                        if (extractedImageUrl == null) {
                            continue; // failed to persist this one image — skip, don't fail the whole upload
                        }
                        String imageSourceName = originalName + " (page " + img.pageNumber() + " image)";
                        ingestionMonos.add(embeddingService.processAndIngest(
                                img.description(), "PDF_IMAGE", imageSourceName, sessionId, extractedImageUrl));
                    }

                    log.info("Successfully processed '{}' ({} text chunk-source, {} embedded images)",
                            originalName, parsed.text() == null || parsed.text().isBlank() ? 0 : 1, parsed.images().size());

                } else if (type == AttachmentType.TEXT) {
                    String parsedText = parserService.parseTextFile(dest);
                    if (parsedText != null && !parsedText.isBlank()) {
                        ingestionMonos.add(embeddingService.processAndIngest(parsedText, type.name(), originalName, sessionId));
                        log.info("Successfully processed '{}'", originalName);
                    }
                } else if (type == AttachmentType.IMAGE) {
                    // A directly uploaded image (photo, chart, graph, screenshot, ...).
                    // There's no text to extract, so Gemini Vision generates a detailed
                    // textual description, which is then chunked and embedded exactly
                    // like any other document text — tagged with this image's own
                    // public URL so citations can render the actual image.
                    byte[] imageBytes = Files.readAllBytes(dest);
                    String parsedText = imageVisionService.describeImage(imageBytes, contentType).block();
                    if (parsedText != null && !parsedText.isBlank()) {
                        ingestionMonos.add(
                                embeddingService.processAndIngest(parsedText, type.name(), originalName, sessionId, publicUrl));
                        log.info("Successfully processed '{}'", originalName);
                    }
                }
            } catch (Exception e) {
                log.error("Failed to parse/ingest '{}': {}", originalName, e.getMessage());
                // We proceed even if parsing fails so the attachment is still recorded
            }

            Mono<Void> ingestionMono = ingestionMonos.isEmpty()
                    ? Mono.empty()
                    : Mono.when(ingestionMonos);

            long sizeBytes;
            try {
                sizeBytes = Files.size(dest);
            } catch (IOException e) {
                sizeBytes = -1L;
            }

            // 5. Create a system Message to anchor the attachment
            Message systemMsg = Message.builder()
                    .session(session)
                    .role(MessageRole.USER)
                    .content("[file upload: " + originalName + "]")
                    .status(MessageStatus.COMPLETE)
                    .createdAt(LocalDateTime.now())
                    .build();
            messageRepository.save(systemMsg);

            // 6. Persist the Attachment record
            Attachment attachment = Attachment.builder()
                    .message(systemMsg)
                    .type(type)
                    .fileName(originalName)
                    .storagePath(storagePath)
                    .url(publicUrl)
                    .mimeType(contentType)
                    .fileSizeBytes(sizeBytes)
                    .uploadedAt(LocalDateTime.now())
                    .build();
            attachmentRepository.save(attachment);

            return new AttachmentUploadResult(toResponse(attachment), ingestionMono);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Persists a single image extracted from inside a PDF to
     * {@code storage/images/extracted/} and returns its public {@code /files/...}
     * URL, so a citation referencing that image's chunk can render it. Returns
     * null (rather than throwing) on failure, so one bad image never fails the
     * whole PDF's ingestion.
     */
    private String saveExtractedPdfImage(DocumentParserService.ExtractedImage img, String originalPdfName, int imgIndex) {
        try {
            Path dir = absoluteStorageRoot.resolve("images").resolve("extracted");
            Files.createDirectories(dir);

            String ext = img.mimeType() != null && img.mimeType().contains("png") ? "png" : "jpg";
            String baseName = originalPdfName.replaceAll("[^a-zA-Z0-9.-]", "_");
            String storedName = UUID.randomUUID() + "_" + baseName + "_p" + img.pageNumber() + "_" + imgIndex + "." + ext;
            Path dest = dir.resolve(storedName);

            Files.write(dest, img.imageBytes());

            String storagePath = "images/extracted/" + storedName;
            return "/files/" + storagePath;
        } catch (IOException e) {
            log.error("Failed to persist extracted PDF image (page {}): {}", img.pageNumber(), e.getMessage());
            return null;
        }
    }

    public Mono<AttachmentUploadResult> uploadWikipedia(Long sessionId, String userEmail, String url) {
        return Mono.fromCallable(() -> {
            // 1. Verify session belongs to caller
            Session session = sessionRepository.findById(sessionId)
                    .orElseThrow(() -> new RuntimeException("Session not found"));

            if (!session.getUser().getEmail().equals(userEmail)) {
                throw new RuntimeException("Access denied");
            }

            // Extract title from URL (e.g. https://en.wikipedia.org/wiki/Spider-Man -> Spider-Man)
            String pageTitle = url.substring(url.lastIndexOf("/") + 1);
            String originalName = java.net.URLDecoder.decode(pageTitle, java.nio.charset.StandardCharsets.UTF_8);

            // Fetch the text from Wikipedia
            String parsedText = null;
            try {
                parsedText = parserService.fetchWikipedia(originalName);
            } catch (Exception e) {
                log.error("Failed to fetch Wikipedia page '{}': {}", originalName, e.getMessage());
            }

            Mono<Void> ingestionMono = Mono.empty();
            if (parsedText != null && !parsedText.isBlank()) {
                ingestionMono = embeddingService.processAndIngest(parsedText, AttachmentType.WIKIPEDIA.name(), originalName, sessionId);
                log.info("Successfully fetched and started ingestion for '{}'", originalName);
            } else {
                throw new RuntimeException("Could not extract text from Wikipedia page.");
            }

            // 5. Create a system Message to anchor the attachment
            Message systemMsg = Message.builder()
                    .session(session)
                    .role(MessageRole.USER)
                    .content("[wikipedia link: " + originalName + "]")
                    .status(MessageStatus.COMPLETE)
                    .createdAt(LocalDateTime.now())
                    .build();
            messageRepository.save(systemMsg);

            // 6. Persist the Attachment record
            Attachment attachment = Attachment.builder()
                    .message(systemMsg)
                    .type(AttachmentType.WIKIPEDIA)
                    .fileName(originalName)
                    .storagePath(url)
                    .url(url)
                    .mimeType("text/html")
                    .fileSizeBytes((long) parsedText.length())
                    .uploadedAt(LocalDateTime.now())
                    .build();
            attachmentRepository.save(attachment);

            return new AttachmentUploadResult(toResponse(attachment), ingestionMono);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // ── List ──────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<AttachmentResponse> getSessionAttachments(Long sessionId, String userEmail) {
        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("Session not found"));

        if (!session.getUser().getEmail().equals(userEmail)) {
            throw new RuntimeException("Access denied");
        }

        return attachmentRepository.findBySessionId(sessionId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private AttachmentType resolveType(String contentType) {
        String ct = contentType.toLowerCase();
        if (ct.contains("pdf"))                                      return AttachmentType.PDF;
        if (ct.startsWith("image/"))                                 return AttachmentType.IMAGE;
        if (ct.contains("text/") || ct.contains("markdown"))        return AttachmentType.TEXT;
        return AttachmentType.OTHER;
    }

    private String resolveFolder(AttachmentType type) {
        return switch (type) {
            case PDF   -> "pdfs";
            case IMAGE -> "images";
            case TEXT  -> "texts";
            default    -> "other";
        };
    }

    private AttachmentResponse toResponse(Attachment a) {
        return AttachmentResponse.builder()
                .attachmentId(a.getAttachmentId())
                .messageId(a.getMessage().getMessageId())
                .type(a.getType())
                .fileName(a.getFileName())
                .storagePath(a.getStoragePath())
                .url(a.getUrl())
                .mimeType(a.getMimeType())
                .fileSizeBytes(a.getFileSizeBytes())
                .uploadedAt(a.getUploadedAt())
                .build();
    }
}
