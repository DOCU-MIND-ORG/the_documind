package com.accenture.intern.docmind.service;

import com.accenture.intern.docmind.entity.Message;
import com.accenture.intern.docmind.entity.MessageRole;
import com.accenture.intern.docmind.entity.Session;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders a chat session (summary + full Q&A transcript) into a downloadable
 * PDF using PDFBox, with a small hand-rolled Markdown renderer so the model's
 * output actually looks like a document instead of dumping raw "**bold**",
 * "### heading", "* bullet" and "| table | syntax |" characters onto the
 * page. PDFBox itself has no rich-text/markdown support, and the project's
 * only Markdown dependency (commonmark-java) doesn't include the GFM table
 * extension needed for the pipe-table syntax the model produces, so block
 * parsing (headings/bullets/tables/rules) and inline bold spans are both
 * handled directly here.
 * <p>
 * Runs entirely on the {@link com.accenture.intern.docmind.service.PdfExportWorkerService}
 * worker thread — never inline in an HTTP request.
 */
@Slf4j
@Service
public class PdfGeneratorService {

    private static final float PAGE_MARGIN = 50f;
    private static final float TITLE_FONT_SIZE = 20f;
    private static final float HEADING_FONT_SIZE = 13f;
    private static final float SUBHEADING_FONT_SIZE = 11.5f;
    private static final float BODY_FONT_SIZE = 10.5f;
    private static final float META_FONT_SIZE = 9f;
    private static final float LINE_LEADING = 14f;
    private static final float SECTION_GAP = 18f;
    private static final float TABLE_CELL_PADDING = 5f;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("MMM d, yyyy 'at' h:mm a");

    // Matches **bold** spans for inline parsing within a single line.
    private static final Pattern BOLD_PATTERN = Pattern.compile("\\*\\*(.+?)\\*\\*");

    /**
     * @param session       the session being exported (for title + created date)
     * @param summary       LLM-generated executive summary paragraph
     * @param messages      full chronological message history (user + assistant)
     * @return the rendered PDF as raw bytes, ready to upload or stream back
     */
    public byte[] generateSessionPdf(Session session, String summary, List<Message> messages) throws IOException {
        try (PDDocument document = new PDDocument()) {
            Fonts fonts = new Fonts(PDType1Font.HELVETICA, PDType1Font.HELVETICA_BOLD, PDType1Font.HELVETICA_OBLIQUE);
            PageCursor cursor = new PageCursor(document, fonts);

            String title = session.getTitle() != null && !session.getTitle().isBlank()
                    ? session.getTitle() : "Chat Session";

            // ---- Title ----
            cursor.writePlainLine(title, fonts.bold, TITLE_FONT_SIZE, TITLE_FONT_SIZE + 6);
            String createdLine = session.getCreatedAt() != null
                    ? "Created " + session.getCreatedAt().format(DATE_FMT)
                    : "";
            if (!createdLine.isBlank()) {
                cursor.writePlainLine(createdLine, fonts.italic, META_FONT_SIZE, LINE_LEADING);
            }
            cursor.advance(SECTION_GAP);
            cursor.drawDivider();
            cursor.advance(SECTION_GAP);

            // ---- Summary section ----
            cursor.writePlainLine("Session summary", fonts.bold, HEADING_FONT_SIZE, HEADING_FONT_SIZE + 4);
            cursor.advance(6f);
            cursor.renderMarkdownBlock(summary == null || summary.isBlank()
                    ? "No summary available for this session." : summary, BODY_FONT_SIZE);
            cursor.advance(SECTION_GAP);
            cursor.drawDivider();
            cursor.advance(SECTION_GAP);

            // ---- Conversation transcript ----
            cursor.writePlainLine("Conversation", fonts.bold, HEADING_FONT_SIZE, HEADING_FONT_SIZE + 4);
            cursor.advance(8f);

            boolean anyMessage = false;
            for (Message m : messages) {
                String content = m.getContent();
                if (content == null || content.isBlank()) continue;

                if (content.startsWith("[file upload:") || content.startsWith("[wikipedia link:")) {
                    cursor.writePlainLine(stripBrackets(content), fonts.italic, META_FONT_SIZE, LINE_LEADING);
                    cursor.advance(8f);
                    continue;
                }

                anyMessage = true;
                String label = m.getRole() == MessageRole.USER ? "You" : "DocMind";
                cursor.writePlainLine(label, fonts.bold, BODY_FONT_SIZE, LINE_LEADING);
                cursor.renderMarkdownBlock(stripCitationMarkers(content), BODY_FONT_SIZE);
                cursor.advance(10f);
            }

            if (!anyMessage) {
                cursor.writePlainLine("No messages were exchanged in this session.", fonts.italic, BODY_FONT_SIZE, LINE_LEADING);
            }

            cursor.close();

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    /** Strips the leading "[file upload: " / "[wikipedia link: " wrapper down to a readable attachment line. */
    private String stripBrackets(String raw) {
        String text = raw;
        if (text.startsWith("[") && text.endsWith("]")) {
            text = text.substring(1, text.length() - 1);
        }
        return "Attachment - " + text;
    }

    /** Strips [CITE: ...] markers before markdown parsing — they carry no useful info in a static PDF. */
    private String stripCitationMarkers(String content) {
        return content.replaceAll("\\[CITE:\\s*[\\d,\\s]+]", "");
    }

    private record Fonts(PDFont regular, PDFont bold, PDFont italic) {}

    /** A run of text in a single style (bold or not), used for mixed-font line rendering. */
    private record Span(String text, boolean bold) {}

    /**
     * Tracks the current page/content-stream and y-position, opening new
     * pages automatically as content overflows, and renders a small Markdown
     * subset (headings, bold spans, bullet lists, pipe tables, horizontal
     * rules, plain paragraphs) since PDFBox has no rich-text support at all.
     */
    private static class PageCursor {
        private final PDDocument document;
        private final Fonts fonts;
        private PDPage currentPage;
        private PDPageContentStream stream;
        private float y;
        private final float usableWidth;

        PageCursor(PDDocument document, Fonts fonts) throws IOException {
            this.document = document;
            this.fonts = fonts;
            this.usableWidth = PDRectangle.LETTER.getWidth() - 2 * PAGE_MARGIN;
            newPage();
        }

        private void newPage() throws IOException {
            if (stream != null) {
                stream.close();
            }
            currentPage = new PDPage(PDRectangle.LETTER);
            document.addPage(currentPage);
            stream = new PDPageContentStream(document, currentPage);
            y = PDRectangle.LETTER.getHeight() - PAGE_MARGIN;
        }

        void advance(float amount) throws IOException {
            y -= amount;
            if (y < PAGE_MARGIN) {
                newPage();
            }
        }

        void drawDivider() throws IOException {
            stream.setLineWidth(0.5f);
            stream.setStrokingColor(200, 200, 200);
            stream.moveTo(PAGE_MARGIN, y);
            stream.lineTo(PDRectangle.LETTER.getWidth() - PAGE_MARGIN, y);
            stream.stroke();
        }

        // ── Block-level Markdown parsing ─────────────────────────────────

        /**
         * Parses a Markdown-ish block of text line by line and renders each
         * construct appropriately: "### "/"## " headings, "* "/"- " bullets,
         * "| a | b |" pipe-table rows (collected as a run and drawn as a
         * bordered grid), "---"/"***" horizontal rules, and plain paragraphs
         * (with inline **bold** spans).
         */
        void renderMarkdownBlock(String text, float fontSize) throws IOException {
            if (text == null || text.isBlank()) return;

            String[] rawLines = text.split("\n");
            List<String> tableBuffer = new ArrayList<>();

            for (String rawLine : rawLines) {
                String line = rawLine.trim();

                boolean isTableRow = line.startsWith("|") && line.endsWith("|") && line.length() > 1;
                if (isTableRow) {
                    tableBuffer.add(line);
                    continue;
                } else if (!tableBuffer.isEmpty()) {
                    // Table run just ended — render it before continuing with this line.
                    renderTable(tableBuffer, fontSize);
                    tableBuffer.clear();
                }

                if (line.isEmpty()) {
                    advance(fontSize * 0.6f);
                } else if (line.matches("^(\\*{3,}|-{3,}|_{3,})$")) {
                    advance(6f);
                    drawDivider();
                    advance(10f);
                } else if (line.startsWith("### ")) {
                    advance(4f);
                    writeInlineWrapped(line.substring(4).trim(), SUBHEADING_FONT_SIZE, SUBHEADING_FONT_SIZE + 5, 0f, true);
                    advance(2f);
                } else if (line.startsWith("## ") || line.startsWith("# ")) {
                    advance(5f);
                    String headingText = line.startsWith("## ") ? line.substring(3).trim() : line.substring(2).trim();
                    writeInlineWrapped(headingText, HEADING_FONT_SIZE, HEADING_FONT_SIZE + 5, 0f, true);
                    advance(2f);
                } else if (line.startsWith("* ") || line.startsWith("- ")) {
                    String bulletText = line.substring(2).trim();
                    writeBullet(bulletText, fontSize);
                } else if (line.matches("^\\d+\\.\\s.*")) {
                    // numbered list item, e.g. "1. Something"
                    writeInlineWrapped(line, fontSize, LINE_LEADING, 0f, false);
                } else {
                    writeInlineWrapped(line, fontSize, LINE_LEADING, 0f, false);
                }
            }

            // A table at the very end of the block never hit the "row ended" branch above.
            if (!tableBuffer.isEmpty()) {
                renderTable(tableBuffer, fontSize);
            }
        }

        private void writeBullet(String text, float fontSize) throws IOException {
            float indent = 14f;

            List<Span> spans = parseInlineSpans(text);
            List<List<Span>> wrapped = wrapSpans(spans, fontSize, usableWidth - indent);

            for (int i = 0; i < wrapped.size(); i++) {
                if (y < PAGE_MARGIN) newPage();
                float x = PAGE_MARGIN + indent;
                if (i == 0) {
                    // plain dash bullet glyph, drawn just to the left of the indent
                    stream.beginText();
                    stream.setFont(fonts.regular, fontSize);
                    stream.newLineAtOffset(PAGE_MARGIN, y);
                    stream.showText("-");
                    stream.endText();
                }
                drawSpans(wrapped.get(i), x, fontSize);
                y -= LINE_LEADING;
            }
        }

        /** Parses inline **bold** spans, wraps to the page width, and draws each wrapped line. */
        private void writeInlineWrapped(String text, float fontSize, float leading, float extraIndent, boolean bold) throws IOException {
            List<Span> spans = bold
                    ? List.of(new Span(text, true))
                    : parseInlineSpans(text);

            List<List<Span>> wrapped = wrapSpans(spans, fontSize, usableWidth - extraIndent);
            for (List<Span> lineSpans : wrapped) {
                if (y < PAGE_MARGIN) newPage();
                drawSpans(lineSpans, PAGE_MARGIN + extraIndent, fontSize);
                y -= leading;
            }
        }

        /** Splits "...**bold**..." into a sequence of plain/bold Spans for one logical line/paragraph. */
        private List<Span> parseInlineSpans(String text) {
            List<Span> spans = new ArrayList<>();
            Matcher matcher = BOLD_PATTERN.matcher(text);
            int last = 0;
            while (matcher.find()) {
                if (matcher.start() > last) {
                    spans.add(new Span(text.substring(last, matcher.start()), false));
                }
                spans.add(new Span(matcher.group(1), true));
                last = matcher.end();
            }
            if (last < text.length()) {
                spans.add(new Span(text.substring(last), false));
            }
            if (spans.isEmpty()) {
                spans.add(new Span(text, false));
            }
            return spans;
        }

        /**
         * Word-wraps a sequence of styled Spans to the given width, preserving
         * bold/plain boundaries mid-line. Returns a list of lines, each itself a
         * list of Spans to render left-to-right.
         */
        private List<List<Span>> wrapSpans(List<Span> spans, float fontSize, float maxWidth) throws IOException {
            List<List<Span>> lines = new ArrayList<>();
            List<Span> currentLine = new ArrayList<>();
            float currentWidth = 0f;

            for (Span span : spans) {
                PDFont font = span.bold() ? fonts.bold : fonts.regular;
                String[] words = span.text().split(" ");
                for (int i = 0; i < words.length; i++) {
                    String word = words[i];
                    if (word.isEmpty()) continue;
                    String piece = (i < words.length - 1 || !isLastSpan(spans, span)) ? word + " " : word;
                    float pieceWidth = font.getStringWidth(sanitize(piece)) / 1000f * fontSize;

                    if (currentWidth + pieceWidth > maxWidth && !currentLine.isEmpty()) {
                        lines.add(currentLine);
                        currentLine = new ArrayList<>();
                        currentWidth = 0f;
                    }
                    currentLine.add(new Span(piece, span.bold()));
                    currentWidth += pieceWidth;
                }
            }
            if (!currentLine.isEmpty()) {
                lines.add(currentLine);
            }
            if (lines.isEmpty()) {
                lines.add(List.of(new Span("", false)));
            }
            return lines;
        }

        private boolean isLastSpan(List<Span> spans, Span span) {
            return spans.get(spans.size() - 1) == span;
        }

        /** Draws a single wrapped line's worth of styled Spans, left-to-right starting at x. */
        private void drawSpans(List<Span> spans, float x, float fontSize) throws IOException {
            float cursorX = x;
            for (Span span : spans) {
                if (span.text().isEmpty()) continue;
                PDFont font = span.bold() ? fonts.bold : fonts.regular;
                stream.beginText();
                stream.setFont(font, fontSize);
                stream.newLineAtOffset(cursorX, y);
                stream.showText(sanitize(span.text()));
                stream.endText();
                cursorX += font.getStringWidth(sanitize(span.text())) / 1000f * fontSize;
            }
        }

        // ── Table rendering ───────────────────────────────────────────────

        /**
         * Renders a buffered run of "| a | b |" lines as a bordered grid. The
         * standard Markdown header-separator row ("|---|---|") is detected and
         * skipped (it carries no content, just marks column alignment).
         */
        private void renderTable(List<String> rawRows, float fontSize) throws IOException {
            List<List<String>> rows = new ArrayList<>();
            for (String raw : rawRows) {
                if (raw.matches("^\\|[\\s:|-]+\\|$")) continue; // separator row like |---|---|
                List<String> cells = new ArrayList<>();
                String inner = raw.substring(1, raw.length() - 1); // strip leading/trailing pipe
                for (String cell : inner.split("\\|")) {
                    cells.add(cell.trim());
                }
                rows.add(cells);
            }
            if (rows.isEmpty()) return;

            int colCount = rows.stream().mapToInt(List::size).max().orElse(1);
            float colWidth = usableWidth / colCount;
            float tableFontSize = Math.max(fontSize - 1.5f, 7.5f);

            advance(6f);
            for (int r = 0; r < rows.size(); r++) {
                List<String> row = rows.get(r);
                boolean isHeaderRow = r == 0;

                // Compute how many wrapped lines this row needs (tallest cell wins).
                List<List<String>> cellLines = new ArrayList<>();
                int maxLines = 1;
                for (int c = 0; c < colCount; c++) {
                    String cellText = c < row.size() ? row.get(c) : "";
                    List<Span> spans = parseInlineSpans(cellText);
                    // strip bold markers visually for table cells (header row is bolded wholesale instead)
                    StringBuilder plain = new StringBuilder();
                    for (Span s : spans) plain.append(s.text());
                    List<String> wrapped = wrapPlainText(plain.toString(), isHeaderRow ? fonts.bold : fonts.regular,
                            tableFontSize, colWidth - 2 * TABLE_CELL_PADDING);
                    cellLines.add(wrapped);
                    maxLines = Math.max(maxLines, wrapped.size());
                }

                float rowHeight = maxLines * (tableFontSize + 3f) + 2 * TABLE_CELL_PADDING;
                if (y - rowHeight < PAGE_MARGIN) {
                    newPage();
                }

                float rowTop = y;
                float rowBottom = y - rowHeight;

                // cell borders + text
                for (int c = 0; c < colCount; c++) {
                    float cellX = PAGE_MARGIN + c * colWidth;
                    stream.setLineWidth(0.5f);
                    stream.setStrokingColor(190, 190, 190);
                    stream.addRect(cellX, rowBottom, colWidth, rowHeight);
                    stream.stroke();

                    if (isHeaderRow) {
                        stream.setNonStrokingColor(235, 235, 240);
                        stream.addRect(cellX + 0.5f, rowBottom + 0.5f, colWidth - 1f, rowHeight - 1f);
                        stream.fill();
                    }

                    List<String> linesForCell = cellLines.get(c);
                    PDFont cellFont = isHeaderRow ? fonts.bold : fonts.regular;
                    float textY = rowTop - TABLE_CELL_PADDING - tableFontSize;
                    for (String lineText : linesForCell) {
                        stream.beginText();
                        stream.setFont(cellFont, tableFontSize);
                        stream.setNonStrokingColor(0, 0, 0);
                        stream.newLineAtOffset(cellX + TABLE_CELL_PADDING, textY);
                        stream.showText(sanitize(lineText));
                        stream.endText();
                        textY -= (tableFontSize + 3f);
                    }
                }

                y = rowBottom;
            }
            advance(10f);
        }

        private List<String> wrapPlainText(String text, PDFont font, float fontSize, float maxWidth) throws IOException {
            List<String> lines = new ArrayList<>();
            String[] words = text.split(" ");
            StringBuilder current = new StringBuilder();
            for (String word : words) {
                if (word.isEmpty()) continue;
                String candidate = current.isEmpty() ? word : current + " " + word;
                float width = font.getStringWidth(sanitize(candidate)) / 1000f * fontSize;
                if (width > maxWidth && !current.isEmpty()) {
                    lines.add(current.toString());
                    current = new StringBuilder(word);
                } else {
                    current = new StringBuilder(candidate);
                }
            }
            if (!current.isEmpty()) lines.add(current.toString());
            if (lines.isEmpty()) lines.add("");
            return lines;
        }

        // ── Plain (non-Markdown) line writer, used for titles/labels/meta ──

        void writePlainLine(String text, PDFont font, float fontSize, float leading) throws IOException {
            if (text == null || text.isBlank()) return;
            for (String paragraph : text.split("\n")) {
                for (String line : wrapPlainText(paragraph, font, fontSize, usableWidth)) {
                    if (y < PAGE_MARGIN) newPage();
                    stream.beginText();
                    stream.setFont(font, fontSize);
                    stream.newLineAtOffset(PAGE_MARGIN, y);
                    stream.showText(sanitize(line));
                    stream.endText();
                    y -= leading;
                }
            }
        }

        /** PDFBox's standard 14 fonts only support WinAnsiEncoding — strip anything outside it
         *  (smart quotes, emoji, etc.) so showText() never throws on an unsupported glyph. */
        private String sanitize(String text) {
            StringBuilder sb = new StringBuilder(text.length());
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c < 256) {
                    sb.append(c);
                } else if (c == '\u2018' || c == '\u2019') {
                    sb.append('\'');
                } else if (c == '\u201C' || c == '\u201D') {
                    sb.append('"');
                } else if (c == '\u2013' || c == '\u2014') {
                    sb.append('-');
                } else if (c == '\u2026') {
                    sb.append("...");
                } else if (c == '\u2022') {
                    sb.append('-'); // safe plain-text fallback for a literal bullet character, if one appears in content
                } else {
                    sb.append('?');
                }
            }
            return sb.toString();
        }

        void close() throws IOException {
            stream.close();
        }
    }
}
