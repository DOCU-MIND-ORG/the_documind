package com.accenture.intern.docmind.service;

import com.accenture.intern.docmind.dto.attachment.AttachmentResponse;
import com.accenture.intern.docmind.dto.attachment.AttachmentUploadResult;
import com.accenture.intern.docmind.entity.*;
import com.accenture.intern.docmind.repository.AttachmentRepository;
import com.accenture.intern.docmind.repository.MessageRepository;
import com.accenture.intern.docmind.repository.SessionRepository;
import lombok.extern.slf4j.Slf4j;
import com.accenture.intern.docmind.aiservices.embedding.DocumentParserService;
import com.accenture.intern.docmind.aiservices.embedding.EmbeddingService;
import com.accenture.intern.docmind.aiservices.vision.ImageVisionService;
import com.accenture.intern.docmind.aiservices.vision.ImageVisionResponse;
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
    private final com.accenture.intern.docmind.repository.ViewAttachmentRepository viewAttachmentRepository;
    private final MessageRepository messageRepository;
    private final SessionRepository sessionRepository;
    private final DocumentParserService parserService;
    private final EmbeddingService embeddingService;
    private final ImageVisionService imageVisionService;
    private final CloudinaryService cloudinaryService;

    /** Root storage dir — always resolved to absolute path at startup */
    @Value("${app.storage.root:storage}")
    private String storageRoot;

    private Path absoluteStorageRoot;

    public AttachmentService(AttachmentRepository attachmentRepository,
                             com.accenture.intern.docmind.repository.ViewAttachmentRepository viewAttachmentRepository,
                             MessageRepository messageRepository,
                             SessionRepository sessionRepository,
                             DocumentParserService parserService,
                             EmbeddingService embeddingService,
                             ImageVisionService imageVisionService,
                             CloudinaryService cloudinaryService) {
        this.attachmentRepository = attachmentRepository;
        this.viewAttachmentRepository = viewAttachmentRepository;
        this.messageRepository = messageRepository;
        this.sessionRepository = sessionRepository;
        this.parserService = parserService;
        this.embeddingService = embeddingService;
        this.imageVisionService = imageVisionService;
        this.cloudinaryService = cloudinaryService;
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

            // 4. Write file to disk temporarily for parsing (blocking, hence fromCallable + boundedElastic)
            filePart.transferTo(dest).block();

            byte[] fileBytes = Files.readAllBytes(dest);

            String storagePath = null;
            String publicUrl;
            String cloudinaryPublicId = null;
            String cloudinaryResourceType = null;

            if (type == AttachmentType.PDF) {
                CloudinaryService.UploadResult uploaded =
                        cloudinaryService.uploadRaw(fileBytes, "storage/pdfs", originalName);
                publicUrl = uploaded.url();
                cloudinaryPublicId = uploaded.publicId();
                cloudinaryResourceType = "raw";
            } else if (type == AttachmentType.IMAGE) {
                CloudinaryService.UploadResult uploaded =
                        cloudinaryService.uploadImage(fileBytes, "storage/images", originalName);
                publicUrl = uploaded.url();
                cloudinaryPublicId = uploaded.publicId();
                cloudinaryResourceType = "image";
            } else {
                CloudinaryService.UploadResult uploaded =
                        cloudinaryService.uploadRaw(fileBytes, "storage/others", originalName);
                publicUrl = uploaded.url();
                cloudinaryPublicId = uploaded.publicId();
                cloudinaryResourceType = "raw";
            }

            List<Mono<Void>> ingestionMonos = new ArrayList<>();
            try {
                if (type == AttachmentType.PDF) {
                    // Extracts page text, AND separately every embedded chart/graph/
                    // image (described via Gemini Vision) — see DocumentParserService.
                    DocumentParserService.PdfParseResult parsed = parserService.parsePdfWithImages(dest);

                    if (parsed.text() != null && !parsed.text().isBlank()) {
                        ingestionMonos.add(
                                embeddingService.processAndIngest(parsed.text(), type.name(), originalName, publicUrl, sessionId));
                    }

                    int imgIndex = 0;
                    for (DocumentParserService.ExtractedImage img : parsed.images()) {
                        imgIndex++;
                        String extractedImageUrl = saveExtractedPdfImage(img, originalName, imgIndex);
                        if (extractedImageUrl == null) {
                            continue; // failed to persist this one image — skip, don't fail the whole upload
                        }
                        String imageSourceName = originalName + " (page " + img.pageNumber() + " image)";
                        ImageVisionResponse vr = img.visionResponse();
                        String tags = vr.tags() != null ? String.join(",", vr.tags()) : null;
                        
                        ingestionMonos.add(embeddingService.processAndIngest(
                                vr.denseDescription(), "PDF_IMAGE", imageSourceName, vr.suggestedFilename(), vr.assetClassification(), tags, sessionId, extractedImageUrl, publicUrl));
                    }

                    log.info("Successfully processed '{}' ({} text chunk-source, {} embedded images)",
                            originalName, parsed.text() == null || parsed.text().isBlank() ? 0 : 1, parsed.images().size());

                } else if (type == AttachmentType.TEXT) {
                    String parsedText = parserService.parseTextFile(dest);
                    if (parsedText != null && !parsedText.isBlank()) {
                        ingestionMonos.add(embeddingService.processAndIngest(parsedText, type.name(), originalName, publicUrl, sessionId));
                        log.info("Successfully processed '{}'", originalName);
                    }
                } else if (type == AttachmentType.IMAGE) {
                    // A directly uploaded image (photo, chart, graph, screenshot, ...).
                    // There's no text to extract, so Gemini Vision generates a detailed
                    // textual description, which is then chunked and embedded exactly
                    // like any other document text — tagged with this image's own
                    // public URL so citations can render the actual image.
                    byte[] imageBytes = Files.readAllBytes(dest);
                    ImageVisionResponse parsedVision = imageVisionService.describeImage(imageBytes, contentType).block();
                    if (parsedVision != null && parsedVision.denseDescription() != null && !parsedVision.denseDescription().isBlank()) {
                        String tags = parsedVision.tags() != null ? String.join(",", parsedVision.tags()) : null;
                        ingestionMonos.add(
                                embeddingService.processAndIngest(parsedVision.denseDescription(), type.name(), originalName, parsedVision.suggestedFilename(), parsedVision.assetClassification(), tags, sessionId, publicUrl, publicUrl));
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

            // Cleanup local temp file since we have it in Cloudinary now
            try {
                Files.deleteIfExists(dest);
            } catch (IOException ignored) {}

            // 5. Create a system Message so the upload shows up in chat history/transcript
            Message systemMsg = Message.builder()
                    .session(session)
                    .role(MessageRole.USER)
                    .content("[file upload: " + originalName + "]")
                    .status(MessageStatus.COMPLETE)
                    .createdAt(LocalDateTime.now())
                    .build();
            messageRepository.save(systemMsg);

            // 6. Persist the Attachment record, linked directly to the session
            // it was uploaded in (provenance) — this row is part of DocMind's
            // shared, persistent corpus and survives even if that session is
            // later deleted (see SessionService#deleteSession).
            Attachment attachment = Attachment.builder()
                    .session(session)
                    .type(type)
                    .fileName(originalName)
                    .storagePath(storagePath)
                    .url(publicUrl)
                    .cloudinaryPublicId(cloudinaryPublicId)
                    .cloudinaryResourceType(cloudinaryResourceType)
                    .mimeType(contentType)
                    .fileSizeBytes(sizeBytes)
                    .uploadedAt(LocalDateTime.now())
                    .build();
            attachmentRepository.save(attachment);

            // 7. Record this session's membership in the "View Attachments" list.
            // Unlike the Attachment row above, this join row IS deleted when the
            // session is deleted (see ViewAttachmentRepository).
            viewAttachmentRepository.save(ViewAttachment.builder()
                    .session(session)
                    .attachment(attachment)
                    .addedAt(LocalDateTime.now())
                    .build());

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

            String originalName;
            String wikipediaUrl = url;
            if (url.startsWith("http")) {
                // Extract title from URL (e.g. https://en.wikipedia.org/wiki/Spider-Man -> Spider-Man)
                String pageTitle = url.substring(url.lastIndexOf("/") + 1);
                originalName = java.net.URLDecoder.decode(pageTitle, java.nio.charset.StandardCharsets.UTF_8);
            } else {
                originalName = url; // Selected directly by user from frontend search
                wikipediaUrl = "https://en.wikipedia.org/wiki/" + originalName.replace(" ", "_");
            }

            // Fetch the text from Wikipedia
            String parsedText = null;
            try {
                parsedText = parserService.fetchWikipedia(originalName);
            } catch (Exception e) {
                log.error("Failed to fetch Wikipedia page '{}': {}", originalName, e.getMessage());
            }

            Mono<Void> ingestionMono = Mono.empty();
            if (parsedText != null && !parsedText.isBlank()) {
                ingestionMono = embeddingService.processAndIngest(parsedText, AttachmentType.WIKIPEDIA.name(), originalName, wikipediaUrl, sessionId);
                log.info("Successfully fetched and started ingestion for '{}'", originalName);
            } else {
                throw new RuntimeException("Could not extract text from Wikipedia page.");
            }

            // 5. Create a system Message so the link shows up in chat history/transcript
            Message systemMsg = Message.builder()
                    .session(session)
                    .role(MessageRole.USER)
                    .content("[wikipedia link: " + originalName + "]")
                    .status(MessageStatus.COMPLETE)
                    .createdAt(LocalDateTime.now())
                    .build();
            messageRepository.save(systemMsg);

            // 6. Persist the Attachment record, linked directly to the session
            // it was added in (provenance) — survives even if that session is
            // later deleted (see SessionService#deleteSession).
            Attachment attachment = Attachment.builder()
                    .session(session)
                    .type(AttachmentType.WIKIPEDIA)
                    .fileName(originalName)
                    .storagePath(wikipediaUrl)
                    .url(wikipediaUrl)
                    .mimeType("text/html")
                    .fileSizeBytes((long) parsedText.length())
                    .uploadedAt(LocalDateTime.now())
                    .build();
            attachmentRepository.save(attachment);

            // 7. Record this session's membership in the "View Attachments" list.
            viewAttachmentRepository.save(ViewAttachment.builder()
                    .session(session)
                    .attachment(attachment)
                    .addedAt(LocalDateTime.now())
                    .build());

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

        return viewAttachmentRepository.findAttachmentsBySessionId(sessionId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<AttachmentResponse> getAllGlobalAttachments() {
        return attachmentRepository.findAll(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "uploadedAt"))
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    public Mono<List<String>> searchWikipedia(String query) {
        return parserService.searchWikipedia(query);
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
                .sessionId(a.getSession() != null ? a.getSession().getSessionId() : null)
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

