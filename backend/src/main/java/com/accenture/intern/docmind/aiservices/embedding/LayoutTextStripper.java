package com.accenture.intern.docmind.aiservices.embedding;

import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LayoutTextStripper extends PDFTextStripper {

    public record BoundingBox(float x, float y, float width, float height) {}

    public record PdfTextElement(
            String text,
            int pageNumber,
            int docCharStart,
            int docCharEnd,
            int pageCharStart,
            int pageCharEnd,
            BoundingBox bbox,
            float fontSize,
            String fontName
    ) {}

    private final List<PdfTextElement> elements = new ArrayList<>();
    private final Map<Integer, Float> pageHeights = new HashMap<>();
    private int globalCharOffset = 0;
    private int pageCharOffset = 0;
    private int currentPageNumber = 1;

    public LayoutTextStripper() throws IOException {
        super();
        setSortByPosition(true);
    }

    public List<PdfTextElement> getElements() {
        return elements;
    }

    public Map<Integer, Float> getPageHeights() {
        return pageHeights;
    }

    @Override
    protected void startPage(org.apache.pdfbox.pdmodel.PDPage page) throws IOException {
        super.startPage(page);
        this.pageCharOffset = 0;
        this.currentPageNumber = getCurrentPageNo();
        // Capture page height for relative header/footer detection in BlockClassifier
        float height = page.getMediaBox() != null ? page.getMediaBox().getHeight() : 842.0f;
        pageHeights.put(currentPageNumber, height);
    }

    @Override
    protected void writeString(String text, List<TextPosition> textPositions) throws IOException {
        super.writeString(text, textPositions);
        
        if (textPositions == null || textPositions.isEmpty()) {
            return;
        }

        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = Float.MIN_VALUE;
        float maxY = Float.MIN_VALUE;
        
        float maxFontSize = 0;
        String fontName = "";

        for (TextPosition tp : textPositions) {
            float x = tp.getXDirAdj();
            float y = tp.getYDirAdj();
            float w = tp.getWidthDirAdj();
            float h = tp.getHeightDir();

            if (x < minX) minX = x;
            if (y - h < minY) minY = y - h;
            if (x + w > maxX) maxX = x + w;
            if (y > maxY) maxY = y;

            if (tp.getFontSizeInPt() > maxFontSize) {
                maxFontSize = tp.getFontSizeInPt();
                fontName = tp.getFont().getName();
            }
        }

        BoundingBox bbox = new BoundingBox(minX, minY, maxX - minX, maxY - minY);
        
        int textLength = text.length();
        int docStart = globalCharOffset;
        int docEnd = docStart + textLength;
        int pageStart = pageCharOffset;
        int pageEnd = pageStart + textLength;

        elements.add(new PdfTextElement(
                text,
                currentPageNumber,
                docStart,
                docEnd,
                pageStart,
                pageEnd,
                bbox,
                maxFontSize,
                fontName
        ));

        // Note: PDFTextStripper writes text and separators to the output stream.
        // We approximate offsets based on the extracted string length and newline.
        globalCharOffset += textLength + getLineSeparator().length();
        pageCharOffset += textLength + getLineSeparator().length();
    }
}
