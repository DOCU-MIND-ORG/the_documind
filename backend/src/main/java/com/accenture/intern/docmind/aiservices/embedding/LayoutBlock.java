package com.accenture.intern.docmind.aiservices.embedding;

import java.util.List;

/**
 * An intermediate, merged layout unit produced by the pre-classification
 * merging pass in BlockClassifier.
 *
 * A LayoutBlock groups one or more adjacent PdfTextElements that belong to the
 * same visual line or text run:
 *   - Same page
 *   - Y-coordinate within ±3pt of each other
 *   - Same (or very similar) font size
 *
 * This merging step prevents split headings ("Transformer" + "Architecture")
 * from being classified as two separate heading blocks.
 */
public record LayoutBlock(
        /** Concatenated text of all merged elements, space-separated. */
        String text,

        /** Page number (1-indexed) this block appears on. */
        int pageNumber,

        /** Character offset of the first element in the merged block, relative to the full document string. */
        int docCharStart,

        /** Character offset of the last element in the merged block. */
        int docCharEnd,

        /** Dominant (maximum) font size across all merged elements. */
        float fontSize,

        /** Font name of the element with the dominant font size. */
        String fontName,

        /** Bounding box spanning the union of all merged element bounding boxes. */
        LayoutTextStripper.BoundingBox bbox,

        /** Page height in points, used for relative header/footer detection. */
        float pageHeight,

        /** All source elements that were merged into this block. */
        List<LayoutTextStripper.PdfTextElement> sourceElements
) {}
