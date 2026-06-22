package com.accenture.intern.docmind.aiservices;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class DocumentParserService {

    private static final int MIN_IMAGE_DIMENSION_PX = 100;
    private static final int MAX_IMAGES_PER_DOCUMENT = 25;

    private final WebClient webClient = WebClient.create();
    private final ImageVisionService imageVisionService;

    public DocumentParserService(ImageVisionService imageVisionService) {
        this.imageVisionService = imageVisionService;
    }

    public record ExtractedImage(byte[] imageBytes, String mimeType, int pageNumber, String description) {}

    public record PdfParseResult(String text, List<ExtractedImage> images) {}

    public PdfParseResult parsePdfWithImages(Path filePath) throws IOException {
        try (PDDocument doc = PDDocument.load(filePath.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc);
            List<ExtractedImage> images = extractAndDescribeImages(doc);
            return new PdfParseResult(text, images);
        }
    }

    public String parseTextFile(Path filePath) throws IOException {
        return Files.readString(filePath, StandardCharsets.UTF_8);
    }

    public String fetchWikipedia(String pageTitle) {
        String url = "https://en.wikipedia.org/w/api.php?action=query&format=json&prop=extracts&explaintext=1&titles="
                + pageTitle.replace(" ", "_");
        return webClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(String.class)
                .map(json -> {
                    try {
                        com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
                        com.fasterxml.jackson.databind.JsonNode pages = root.path("query").path("pages");
                        if (pages.elements().hasNext()) {
                            return pages.elements().next().path("extract").asText();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    return "";
                })
                .block();
    }

    private List<ExtractedImage> extractAndDescribeImages(PDDocument doc) {
        List<ExtractedImage> results = new ArrayList<>();
        int pageNumber = 0;
        int processedCount = 0;

        for (PDPage page : doc.getPages()) {
            pageNumber++;

            if (processedCount >= MAX_IMAGES_PER_DOCUMENT) {
                log.warn("Reached max images per document ({}), skipping remaining pages",
                        MAX_IMAGES_PER_DOCUMENT);
                break;
            }

            PDResources resources = page.getResources();
            if (resources == null) {
                continue;
            }

            for (COSName xObjectName : resources.getXObjectNames()) {
                if (processedCount >= MAX_IMAGES_PER_DOCUMENT) {
                    break;
                }

                try {
                    PDXObject xObject = resources.getXObject(xObjectName);
                    if (!(xObject instanceof PDImageXObject imageXObject)) {
                        continue;
                    }

                    BufferedImage bufferedImage = imageXObject.getImage();
                    if (bufferedImage == null
                            || bufferedImage.getWidth() < MIN_IMAGE_DIMENSION_PX
                            || bufferedImage.getHeight() < MIN_IMAGE_DIMENSION_PX) {
                        continue;
                    }

                    byte[] pngBytes = toPngBytes(bufferedImage);
                    if (pngBytes == null) {
                        continue;
                    }

                    String description = imageVisionService.describeImage(pngBytes, "image/png").block();
                    processedCount++;

                    if (description != null && !description.isBlank()) {
                        results.add(new ExtractedImage(pngBytes, "image/png", pageNumber, description));
                        log.info("Described image on page {} ({}x{})", pageNumber,
                                bufferedImage.getWidth(), bufferedImage.getHeight());
                    }
                } catch (Exception e) {
                    log.warn("Failed to extract/describe an image on page {}: {}", pageNumber, e.getMessage());
                }
            }
        }

        return results;
    }

    private byte[] toPngBytes(BufferedImage image) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", baos);
            return baos.toByteArray();
        } catch (IOException e) {
            log.warn("Failed to encode extracted image as PNG: {}", e.getMessage());
            return null;
        }
    }
}
