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
import org.springframework.core.io.buffer.DataBufferUtils;
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
    private final CloudinaryService cloudinaryService;

    /** Root storage dir for the attachment types still kept locally (TEXT, OTHER) — always resolved to absolute path at startup */
    @Value("${app.storage.root:storage}")
    private String storageRoot;

    private Path absoluteStorageRoot;

    public AttachmentService(AttachmentRepository attachmentRepository,
                             MessageRepository messageRepository,
                             SessionRepository sessionRepository,
                             DocumentParserService parserService,
                             EmbeddingService embeddingService,
                             ImageVisionService imageVisionService,
                             CloudinaryService cloudinaryService) {
        this.attachmentRepository = attachmentRepository;
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
     * Persists the uploaded file. PDF and IMAGE attachments are uploaded
     * straight to Cloudinary (under storage/pdfs or storage/images,
     * mirroring the old local folder layout) without ever touching local
     * disk. TEXT and OTHER attachments are still written to local disk and
     * served via /files/{storagePath}, exactly as before.
     */
    public Mono<AttachmentUploadResult> uploadFile(Long sessionId, String userEmail, FilePart filePart) {
        // Read the full file into memory once, up front — both the Cloudinary
        // upload path and the local-disk path need the bytes, and FilePart's
        // content can only be consumed once.
        return DataBufferUtils.join(filePart.content())
                .map(dataBuffer -> {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    DataBufferUtils.release(dataBuffer);
                    return bytes;
                })
                .defaultIfEmpty(new byte[0])
                .flatMap(fileBytes -> Mono.fromCallable(() -> {
                    // 1. Verify session belongs to caller
                    Session session = sessionRepository.findById(sessionId)
                            .orElseThrow(() -> new RuntimeException("Session not found"));

                    if (!session.getUser().getEmail().equals(userEmail)) {
                        throw new RuntimeException("Access denied");
                    }

                    // 2. Determine type from content-type
                    String contentType = filePart.headers().getContentType() != null
                            ? filePart.headers().getContentType().toString()
                            : "application/octet-stream";

                    AttachmentType type = resolveType(contentType);
                    String originalName = filePart.filename();

                    String storagePath = null;   // only set for local types (TEXT/OTHER)
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
                        // TEXT / OTHER — keep on local disk as before
                        String subFolder = resolveFolder(type);
                        Path dir = absoluteStorageRoot.resolve(subFolder);
                        Files.createDirectories(dir);

                        String storedName = UUID.randomUUID() + "_" + originalName;
                        Path dest = dir.resolve(storedName);
                        Files.write(dest, fileBytes);

                        storagePath = subFolder + "/" + storedName;
                        publicUrl = "/files/" + storagePath;
                    }

                    List<Mono<Void>> ingestionMonos = new ArrayList<>();
                    try {
                        if (type == AttachmentType.PDF) {
                            // Extracts page text, AND separately every embedded chart/graph/
                            // image (described via Gemini Vision) — see DocumentParserService.
                            // Parsed directly from the in-memory bytes — the PDF itself now
                            // lives only on Cloudinary, never on local disk.
                            DocumentParserService.PdfParseResult parsed = parserService.parsePdfWithImages(fileBytes);

                            if (parsed.text() != null && !parsed.text().isBlank()) {
                                ingestionMonos.add(
                                        embeddingService.processAndIngest(parsed.text(), type.name(), originalName, sessionId, null, publicUrl));
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
                            Path dest = absoluteStorageRoot.resolve(storagePath);
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
                            // Cloudinary URL so citations can render the actual image.
                            String parsedText = imageVisionService.describeImage(fileBytes, contentType).block();
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

                    long sizeBytes = fileBytes.length;

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
                            .cloudinaryPublicId(cloudinaryPublicId)
                            .cloudinaryResourceType(cloudinaryResourceType)
                            .mimeType(contentType)
                            .fileSizeBytes(sizeBytes)
                            .uploadedAt(LocalDateTime.now())
                            .build();
                    attachmentRepository.save(attachment);

                    return new AttachmentUploadResult(toResponse(attachment), ingestionMono);
                }).subscribeOn(Schedulers.boundedElastic()));
    }

    /**
     * Uploads a single image extracted from inside a PDF to Cloudinary under
     * storage/images/extracted (mirroring the old local folder layout) and
     * returns its secure_url, so a citation referencing that image's chunk
     * can render it. Returns null (rather than throwing) on failure, so one
     * bad image never fails the whole PDF's ingestion.
     */
    private String saveExtractedPdfImage(DocumentParserService.ExtractedImage img, String originalPdfName, int imgIndex) {
        try {
            String ext = img.mimeType() != null && img.mimeType().contains("png") ? "png" : "jpg";
            String baseName = originalPdfName.replaceAll("[^a-zA-Z0-9.-]", "_");
            String fileName = baseName + "_p" + img.pageNumber() + "_" + imgIndex + "." + ext;

            CloudinaryService.UploadResult uploaded =
                    cloudinaryService.uploadImage(img.imageBytes(), "storage/images/extracted", fileName);
            return uploaded.url();
        } catch (Exception e) {
            log.error("Failed to upload extracted PDF image to Cloudinary (page {}): {}", img.pageNumber(), e.getMessage());
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
