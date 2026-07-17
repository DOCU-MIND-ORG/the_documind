package com.accenture.intern.docmind.aiservices.chat;

public class GenerationCancelledException extends RuntimeException {
    private final String partialContent;

    public GenerationCancelledException() {
        super("Generation was cancelled");
        this.partialContent = "";
    }

    public GenerationCancelledException(String partialContent) {
        super("Generation was cancelled");
        this.partialContent = partialContent;
    }
    
    public String getPartialContent() {
        return partialContent;
    }
}
