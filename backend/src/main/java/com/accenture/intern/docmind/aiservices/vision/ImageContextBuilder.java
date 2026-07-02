package com.accenture.intern.docmind.aiservices.vision;

public class ImageContextBuilder {

    public static String buildPdfContext(String previousText, String currentText, String nextText) {
        StringBuilder sb = new StringBuilder();
        sb.append("Source: Enterprise Document (PDF)\n");
        if (previousText != null && !previousText.isBlank()) {
            sb.append("\n--- PREVIOUS SECTION ---\n").append(previousText.trim());
        }
        if (currentText != null && !currentText.isBlank()) {
            sb.append("\n--- CURRENT SECTION ---\n").append(currentText.trim());
        }
        if (nextText != null && !nextText.isBlank()) {
            sb.append("\n--- NEXT SECTION ---\n").append(nextText.trim());
        }
        return sb.toString();
    }

    public static String buildWikipediaContext(String title, String sectionHeading, String surroundingText, String imageCaption) {
        StringBuilder sb = new StringBuilder();
        sb.append("Source: Wikipedia Article\n");
        sb.append("Title: ").append(title).append("\n");
        if (sectionHeading != null && !sectionHeading.isBlank()) {
            sb.append("Section: ").append(sectionHeading).append("\n");
        }
        if (imageCaption != null && !imageCaption.isBlank()) {
            sb.append("Image Caption: ").append(imageCaption).append("\n");
        }
        if (surroundingText != null && !surroundingText.isBlank()) {
            sb.append("\n--- SURROUNDING TEXT ---\n").append(surroundingText.trim());
        }
        return sb.toString();
    }

    public static String buildStandaloneContext(String filename, String userPrompt) {
        StringBuilder sb = new StringBuilder();
        sb.append("Source: Standalone Upload\n");
        if (filename != null && !filename.isBlank()) {
            sb.append("Filename: ").append(filename).append("\n");
        }
        if (userPrompt != null && !userPrompt.isBlank()) {
            sb.append("User Prompt / Description: ").append(userPrompt).append("\n");
        }
        return sb.toString();
    }
}
