package com.accenture.intern.docmind.aiservices.embedding;

import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Two-stage document layout analyser:
 *
 * Stage 1 — Merge
 *   Groups adjacent PdfTextElements on the same page and Y-line (within ±3pt)
 *   into LayoutBlocks. This prevents split headings from being misclassified as
 *   two separate H1 blocks.
 *
 * Stage 2 — Classify
 *   Assigns a BlockType to each LayoutBlock using a multi-signal scoring model:
 *     • Font size relative to document median and max
 *     • Bold/heavy/black font name
 *     • Text length (headings are short)
 *     • Numbered prefix (Chapter X, 1.2, §3)
 *     • ALL CAPS
 *     • Left-alignment (x < 15% of page width)
 *     • Header/footer zone (top/bottom 5% of page height) + repetition check
 *     • List prefix (•, -, *, –, 1., a.)
 *     • TOC entry pattern (text .... 42)
 *     • Monospace font (code)
 *
 * TABLE and FIGURE_CAPTION are intentionally not produced. They are declared in
 * BlockType as placeholders for future detection logic.
 */
@Slf4j
public class BlockClassifier {

    /** Y-coordinate tolerance (points) for merging elements into the same line. */
    private static final float Y_MERGE_TOLERANCE = 3.0f;

    /** Minimum classification score to treat a block as a heading. */
    private static final int HEADING_SCORE_THRESHOLD = 3;

    /** Regex: numbered prefix patterns — "1.", "1.2", "Chapter 3", "Section IV", "Law 4" */
    private static final java.util.regex.Pattern NUMBERED_PREFIX =
            java.util.regex.Pattern.compile(
                    "^(chapter|section|part|law|rule|lesson|article|unit|module|topic|appendix)\\s+\\S.*",
                    java.util.regex.Pattern.CASE_INSENSITIVE
            );
    private static final java.util.regex.Pattern NUMERIC_PREFIX =
            java.util.regex.Pattern.compile("^\\d+(\\.\\d+)*\\.?\\s+\\S.*");

    /** Regex: TOC entry — "Some text ..... 42" */
    private static final java.util.regex.Pattern TOC_ENTRY =
            java.util.regex.Pattern.compile(".*\\.{3,}\\s*\\d+\\s*$");

    /** Regex: bullet list prefix */
    private static final java.util.regex.Pattern BULLET_PREFIX =
            java.util.regex.Pattern.compile("^[•\\-*–▪◦►▶→✓✗✦]\\s+.+");

    /** Regex: numbered list item — "1.", "a.", "i." */
    private static final java.util.regex.Pattern LIST_ITEM_PREFIX =
            java.util.regex.Pattern.compile("^(\\d+|[a-zA-Z]|[ivxlcdmIVXLCDM]+)[.):]\\s+.+");

    /** Minimum number of pages a header/footer text must repeat on to be confirmed as noise. */
    private static final int HEADER_FOOTER_REPEAT_THRESHOLD = 3;

    private BlockClassifier() {}

    /**
     * Entry point: takes raw PdfTextElements and per-page heights, returns a
     * fully classified, reading-order list of DocumentBlocks.
     *
     * @param elements   all elements extracted by LayoutTextStripper
     * @param pageHeights map from page number (1-based) to page height in points
     */
    public static List<DocumentBlock> classify(
            List<LayoutTextStripper.PdfTextElement> elements,
            Map<Integer, Float> pageHeights) {

        if (elements == null || elements.isEmpty()) return List.of();

        // Stage 1: merge elements into layout blocks
        List<LayoutBlock> layoutBlocks = mergeIntoLayoutBlocks(elements, pageHeights);

        // Compute document-level font statistics (needed for heading scoring)
        FontStats fontStats = computeFontStats(layoutBlocks);

        // Build a repetition index: text → set of page numbers it appears on
        Map<String, Set<Integer>> textPageOccurrences = buildRepetitionIndex(layoutBlocks);

        // Stage 2: classify each layout block
        List<DocumentBlock> result = new ArrayList<>();
        int readingOrder = 0;

        for (LayoutBlock block : layoutBlocks) {
            BlockType type = classifyBlock(block, fontStats, textPageOccurrences, pageHeights);
            int level = headingLevel(type, block, fontStats);

            result.add(new DocumentBlock(
                    type,
                    block.text(),
                    block.pageNumber(),
                    block.docCharStart(),
                    block.docCharEnd(),
                    block.fontSize(),
                    block.fontName(),
                    block.bbox(),
                    level,
                    readingOrder++
            ));
        }

        log.debug("BlockClassifier: {} elements → {} layout blocks → {} document blocks",
                elements.size(), layoutBlocks.size(), result.size());

        return result;
    }

    // ─── Stage 1: Merge ────────────────────────────────────────────────────────

    private static List<LayoutBlock> mergeIntoLayoutBlocks(
            List<LayoutTextStripper.PdfTextElement> elements,
            Map<Integer, Float> pageHeights) {

        List<LayoutBlock> blocks = new ArrayList<>();
        if (elements.isEmpty()) return blocks;

        // Work through elements in extraction order (LayoutTextStripper already sorts by position)
        List<LayoutTextStripper.PdfTextElement> current = new ArrayList<>();
        current.add(elements.get(0));

        for (int i = 1; i < elements.size(); i++) {
            LayoutTextStripper.PdfTextElement el = elements.get(i);
            LayoutTextStripper.PdfTextElement prev = current.get(current.size() - 1);

            boolean samePage = el.pageNumber() == prev.pageNumber();
            boolean sameYLine = Math.abs(el.bbox().y() - prev.bbox().y()) <= Y_MERGE_TOLERANCE;
            boolean similarFont = Math.abs(el.fontSize() - prev.fontSize()) <= 1.0f;

            if (samePage && sameYLine && similarFont) {
                current.add(el);
            } else {
                blocks.add(buildLayoutBlock(current, pageHeights));
                current = new ArrayList<>();
                current.add(el);
            }
        }
        if (!current.isEmpty()) {
            blocks.add(buildLayoutBlock(current, pageHeights));
        }
        return blocks;
    }

    private static LayoutBlock buildLayoutBlock(
            List<LayoutTextStripper.PdfTextElement> elements,
            Map<Integer, Float> pageHeights) {

        String text = elements.stream()
                .map(e -> e.text() == null ? "" : e.text())
                .collect(Collectors.joining(" "))
                .trim()
                .replaceAll("\\s+", " ");

        int page = elements.get(0).pageNumber();
        int docCharStart = elements.get(0).docCharStart();
        int docCharEnd = elements.get(elements.size() - 1).docCharEnd();

        // Dominant font = max font size in the group
        float maxFont = 0;
        String fontName = "";
        for (LayoutTextStripper.PdfTextElement el : elements) {
            if (el.fontSize() > maxFont) {
                maxFont = el.fontSize();
                fontName = el.fontName() != null ? el.fontName() : "";
            }
        }

        // Union bounding box
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = Float.MIN_VALUE, maxY = Float.MIN_VALUE;
        for (LayoutTextStripper.PdfTextElement el : elements) {
            LayoutTextStripper.BoundingBox b = el.bbox();
            if (b.x() < minX) minX = b.x();
            if (b.y() < minY) minY = b.y();
            if (b.x() + b.width() > maxX) maxX = b.x() + b.width();
            if (b.y() + b.height() > maxY) maxY = b.y() + b.height();
        }
        LayoutTextStripper.BoundingBox unionBbox =
                new LayoutTextStripper.BoundingBox(minX, minY, maxX - minX, maxY - minY);

        float pageHeight = pageHeights.getOrDefault(page, 842.0f); // A4 default

        return new LayoutBlock(text, page, docCharStart, docCharEnd,
                maxFont, fontName, unionBbox, pageHeight, elements);
    }

    // ─── Stage 2: Classify ─────────────────────────────────────────────────────

    private static BlockType classifyBlock(
            LayoutBlock block,
            FontStats stats,
            Map<String, Set<Integer>> textPageOccurrences,
            Map<Integer, Float> pageHeights) {

        String text = block.text();
        if (text == null || text.isBlank()) return BlockType.UNKNOWN;

        String trimmed = text.trim();

        // --- Noise: header / footer zone + repetition ---
        float pageH = block.pageHeight();
        float y = block.bbox().y();
        boolean inHeaderZone = y < pageH * 0.05f;
        boolean inFooterZone = y > pageH * 0.92f;

        if (inHeaderZone || inFooterZone) {
            String normalized = trimmed.toLowerCase().replaceAll("\\d+", "#");
            Set<Integer> pages = textPageOccurrences.getOrDefault(normalized, Set.of());
            if (pages.size() >= HEADER_FOOTER_REPEAT_THRESHOLD) {
                // Repeated text near top/bottom → confirmed header or footer
                if (trimmed.matches("\\d+") || trimmed.matches("- \\d+ -") || trimmed.matches("page \\d+.*")) {
                    return BlockType.PAGE_NUMBER;
                }
                return inHeaderZone ? BlockType.HEADER : BlockType.FOOTER;
            }
        }

        // --- Page number (standalone digit line anywhere, very short) ---
        if (trimmed.matches("^-?\\s*\\d+\\s*-?$") && trimmed.length() <= 6) {
            return BlockType.PAGE_NUMBER;
        }

        // --- TOC entry ---
        if (TOC_ENTRY.matcher(trimmed).matches()) {
            return BlockType.TOC_ENTRY;
        }

        // --- Bullet list ---
        if (BULLET_PREFIX.matcher(trimmed).matches()) {
            return BlockType.BULLET_LIST;
        }

        // --- Numbered list item ---
        if (LIST_ITEM_PREFIX.matcher(trimmed).matches() && trimmed.length() < 300) {
            return BlockType.NUMBERED_LIST;
        }

        // --- Code block (monospace font heuristic) ---
        String fontLower = block.fontName().toLowerCase();
        if (fontLower.contains("courier") || fontLower.contains("mono") || fontLower.contains("code")) {
            return BlockType.CODE_BLOCK;
        }

        // --- Heading multi-signal scoring ---
        int score = headingScore(block, stats);
        if (score >= HEADING_SCORE_THRESHOLD) {
            float fs = block.fontSize();
            // Determine heading level inline to pick the right type
            if (fs >= stats.maxFontSize * 0.90f) return BlockType.TITLE;
            if (fs >= stats.medianFontSize * 1.30f || score >= 6) return BlockType.H1;
            if (fs >= stats.medianFontSize * 1.15f || score >= 4) return BlockType.H2;
            return BlockType.H3;
        }

        return BlockType.PARAGRAPH;
    }

    /**
     * Computes heading score (0–n). Called from both classifyBlock (to detect
     * headings) and headingLevel (to decide H1/H2/H3).
     */
    private static int headingScore(LayoutBlock block, FontStats stats) {
        String trimmed = block.text().trim();
        float fs = block.fontSize();
        int score = 0;

        // Font size signals
        if (fs >= stats.maxFontSize * 0.90f)       score += 4;
        else if (fs >= stats.medianFontSize * 1.40f) score += 3;
        else if (fs >= stats.medianFontSize * 1.20f) score += 2;
        else if (fs >= stats.medianFontSize * 1.10f) score += 1;

        // Bold/heavy font
        String fontLower = block.fontName().toLowerCase();
        if (fontLower.matches(".*(bold|heavy|black|demi|semibold).*")) score += 2;

        // Short line
        if (trimmed.length() < 40) score += 2;
        else if (trimmed.length() < 80) score += 1;

        // Numbered chapter/section prefix
        if (NUMBERED_PREFIX.matcher(trimmed).matches()) score += 3;
        if (NUMERIC_PREFIX.matcher(trimmed).matches()) score += 3;

        // ALL CAPS (and short)
        if (trimmed.equals(trimmed.toUpperCase()) && trimmed.length() < 80
                && trimmed.matches(".*[A-Z].*")) score += 2;

        // Left-aligned (x < ~15% of estimated page width, rough heuristic ~595pt A4)
        if (block.bbox().x() < 90f) score += 1;

        return score;
    }

    /**
     * Returns the heading depth (1–3) for heading block types, 0 for all others.
     */
    private static int headingLevel(BlockType type, LayoutBlock block, FontStats stats) {
        return switch (type) {
            case TITLE -> 1;
            case H1    -> 1;
            case H2    -> 2;
            case H3    -> 3;
            default    -> 0;
        };
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    private static FontStats computeFontStats(List<LayoutBlock> blocks) {
        List<Float> sizes = blocks.stream()
                .map(LayoutBlock::fontSize)
                .filter(f -> f > 0)
                .sorted()
                .collect(Collectors.toList());

        if (sizes.isEmpty()) return new FontStats(12f, 12f);
        float median = sizes.get(sizes.size() / 2);
        float max    = sizes.get(sizes.size() - 1);
        return new FontStats(median, max);
    }

    private static Map<String, Set<Integer>> buildRepetitionIndex(List<LayoutBlock> blocks) {
        Map<String, Set<Integer>> index = new HashMap<>();
        for (LayoutBlock b : blocks) {
            String key = b.text().trim().toLowerCase().replaceAll("\\d+", "#");
            index.computeIfAbsent(key, k -> new HashSet<>()).add(b.pageNumber());
        }
        return index;
    }

    private record FontStats(float medianFontSize, float maxFontSize) {}
}
