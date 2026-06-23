package com.accenture.intern.docmind.aiservices;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@Service
public class DocumentParserService {

    private final WebClient webClient = WebClient.create();

    public String parsePdf(Path filePath) throws IOException {
        try (PDDocument doc = PDDocument.load(filePath.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            
            return stripper.getText(doc);
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
}
