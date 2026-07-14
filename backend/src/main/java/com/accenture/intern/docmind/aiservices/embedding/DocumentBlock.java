package com.accenture.intern.docmind.aiservices.embedding;

/**
 * A fully classified document block ready for the SemanticChunkBuilder.
 *
 * Every DocumentBlock carries its BlockType, the merged text, layout
 * coordinates, and derived hierarchy information (heading level, section
 * depth). The SemanticChunkBuilder uses these fields to group blocks into
 * semantically coherent Spring AI Document chunks.
 */
public record DocumentBlock(

        /** Semantic type assigned by BlockClassifier. */
        BlockType type,

        /** Merged, trimmed text content of this block. */
        String text,

        /** Page number (1-indexed). */
        int pageNumber,

        /** Character offset in the full document string — start. */
        int docCharStart,

        /** Character offset in the full document string — end. */
        int docCharEnd,

        /** Dominant font size across merged elements (in points). */
        float fontSize,

        /** Font name of the dominant element. */
        String fontName,

        /** Bounding box spanning all merged elements. */
        LayoutTextStripper.BoundingBox bbox,

        /**
         * Heading hierarchy level.
         * 0 = not a heading (PARAGRAPH, LIST, etc.)
         * 1 = TITLE or H1
         * 2 = H2
         * 3 = H3
         */
        int headingLevel,

        /**
         * Sequential reading-order index within the document (0-based).
         * Assigned by BlockClassifier in processing order after the merge pass.
         * Used as a stable sort key and exposed as metadata for future retrieval.
         */
        int readingOrder
) {

    /** Convenience: returns true when this block is any heading type. */
    public boolean isHeading() {
        return type == BlockType.TITLE
                || type == BlockType.H1
                || type == BlockType.H2
                || type == BlockType.H3;
    }

    /** Convenience: returns true when this block should be excluded from chunks. */
    public boolean isNoise() {
        return type == BlockType.HEADER
                || type == BlockType.FOOTER
                || type == BlockType.PAGE_NUMBER;
    }

    /** Convenience: returns true when this block is a list item. */
    public boolean isList() {
        return type == BlockType.BULLET_LIST || type == BlockType.NUMBERED_LIST;
    }
}
