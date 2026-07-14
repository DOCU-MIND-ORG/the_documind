package com.accenture.intern.docmind.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "docmind.retrieval")
public class RetrievalProperties {

    private double primaryThreshold = 0.42;
    private double sessionThreshold = 0.35;

    /** Number of anchor candidates to retrieve and group before sectionPath expansion. */
    private int contiguousAnchorK = 5;

    /**
     * Soft character budget for expanded sections. Exceeding this logs a warning but does NOT
     * truncate — the full section is returned and the LLM handles it. ~37k tokens at 4 chars/token.
     */
    private int contiguousMaxChars = 150_000;

    /**
     * Minimum aggregate score for the winning sectionPath group. If no section exceeds this,
     * CONTIGUOUS falls back to RANKED_RETRIEVAL to avoid expanding the wrong chapter.
     */
    private double contiguousMinConfidence = 0.65;

    public double getPrimaryThreshold() { return primaryThreshold; }
    public void setPrimaryThreshold(double primaryThreshold) { this.primaryThreshold = primaryThreshold; }

    public double getSessionThreshold() { return sessionThreshold; }
    public void setSessionThreshold(double sessionThreshold) { this.sessionThreshold = sessionThreshold; }

    public int getContiguousAnchorK() { return contiguousAnchorK; }
    public void setContiguousAnchorK(int contiguousAnchorK) { this.contiguousAnchorK = contiguousAnchorK; }

    public int getContiguousMaxChars() { return contiguousMaxChars; }
    public void setContiguousMaxChars(int contiguousMaxChars) { this.contiguousMaxChars = contiguousMaxChars; }

    public double getContiguousMinConfidence() { return contiguousMinConfidence; }
    public void setContiguousMinConfidence(double contiguousMinConfidence) { this.contiguousMinConfidence = contiguousMinConfidence; }
}

