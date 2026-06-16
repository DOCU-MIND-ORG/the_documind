package com.accenture.intern.docmind.service;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.client.WebClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
@Service
public class DocumentParserService {

    private final WebClient webClient = WebClient.create();
    public String parsePdf(MultipartFile file) throws IOException {
        try (PDDocument doc = PDDocument.load(file.getInputStream())) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(doc);
        }
    }
    public String parseTextFile(MultipartFile file) throws IOException {
        return new String(file.getBytes(), StandardCharsets.UTF_8);
    }

    public String fetchWikipedia(String pageTitle) {
        String url = "https://en.wikipedia.org/api/rest_v1/page/summary/"
                + pageTitle.replace(" ", "_");
        return webClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(WikipediaSummary.class)
                .map(WikipediaSummary::extract)
                .block();
    }

    record WikipediaSummary(String extract) {}
}
