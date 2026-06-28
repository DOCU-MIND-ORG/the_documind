package com.accenture.intern.docmind.util;

public class FilenameNormalizer {
    
    private FilenameNormalizer() {} // prevent instantiation

    public static String normalize(String filename) {
        if (filename == null || filename.isBlank()) {
            return "";
        }
        return filename
            .replaceAll("\\.[^.]+$", "")   // strip extension
            .replaceAll("[_\\-]", " ")       // underscores/hyphens → spaces
            .toLowerCase()
            .trim();
    }
}
