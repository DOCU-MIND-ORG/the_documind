package com.accenture.intern.docmind.aiservices.embedding;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Assembles classified DocumentBlocks into semantically coherent Spring AI
 * Document chunks for embedding.
 *
 * Chunking rules (in priority order):
 *  1. HEADER, FOOTER, PAGE_NUMBER   → skipped, never emitted into any chunk
 *  2. TOC_ENTRY blocks              → grouped together into one "TOC" chunk
 *  3. Any heading block (TITLE/H1/H2/H3) → always starts a new chunk
 *  4. LIST blocks (BULLET/NUMBERED) → accumulated together; closed when a non-list follows
 *  5. PARAGRAPH / UNKNOWN           → accumulated until MAX_SEMANTIC_CHARS, then split at block boundary
 *  6. CODE_BLOCK                    → its own chunk
 *
 * Additionally, one synthetic SECTION_MAP chunk is produced from all detected
 * headings and prepended to the list. This chunk answers overview/navigation
 * questions ("What chapters are in this document?") without requiring a keyword
 * or semantic match against body paragraphs.
 *
 * Metadata attached to every chunk:
 *   blockType, heading, parentHeading, headingLevel, hierarchyDepth,
 *   sectionPath, page, chunkIndex, readingOrder, sessionId, sourceName,
 *   sourceType, originalFileName, enrichedFileName, sourceUrl, sessionId
 */
@Slf4j
public class SemanticChunkBuilder {

    /**
     * Maximum accumulated characters before forcing a paragraph chunk boundary.
     * ~2500 chars ≈ 625 tokens (1 token ≈ 4 chars for English), safely under
     * Gemini Flash's context window while still holding a full section.
     */
    private static final int MAX_SEMANTIC_CHARS = 2500;

    private SemanticChunkBuilder() {}

    /**
     * Main entry point.
     *
     * @param blocks            classified document blocks from BlockClassifier
     * @param sourceType        e.g. "PDF"
     * @param originalFileName  raw filename
     * @param enrichedFileName  enriched/display filename (may equal originalFileName)
     * @param assetClassification nullable asset classification label
     * @param assetTags         nullable asset tags
     * @param sessionId         current session
     * @param sourceUrl         Cloudinary or storage URL
     * @param objectMapper      for serializing bounding boxes
     * @return list of Spring AI Documents ready for Pinecone + Postgres ingest
     */
    public static List<Document> build(
            List<DocumentBlock> blocks,
            String sourceType,
            String originalFileName,
            String enrichedFileName,
            String assetClassification,
            String assetTags,
            Long sessionId,
            String sourceUrl,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper) {

        List<Document> chunks = new ArrayList<>();
        if (blocks == null || blocks.isEmpty()) return chunks;

        String normalizedName = com.accenture.intern.docmind.util.FilenameNormalizer.normalize(originalFileName);

        // --- 1. Synthetic SECTION_MAP chunk ---
        List<DocumentBlock> headingBlocks = blocks.stream()
                .filter(DocumentBlock::isHeading)
                .collect(Collectors.toList());

        if (!headingBlocks.isEmpty()) {
            StringBuilder sectionMap = new StringBuilder();
            sectionMap.append("DOCUMENT_STRUCTURE (Section Map / Outline)\n\n");
            sectionMap.append("This document contains ").append(headingBlocks.size()).append(" structural headings.\n");
            sectionMap.append("These headings outline the core concepts, principles, subjects, and topics discussed in the document, ");
            sectionMap.append("providing a comprehensive list of its chapters, laws, or structural elements.\n\n");
            for (DocumentBlock h : headingBlocks) {
                String indent = "  ".repeat(Math.max(0, h.headingLevel() - 1));
                sectionMap.append(indent).append("- ").append(h.text()).append("\n");
            }
            Map<String, Object> sectionMeta = buildBaseMeta(
                    "SECTION_MAP", normalizedName, originalFileName, enrichedFileName,
                    sourceType, assetClassification, assetTags, sessionId, sourceUrl);
            sectionMeta.put("chunkIndex", -1);
            sectionMeta.put("readingOrder", -1);
            sectionMeta.put("keywords", List.of("table of contents", "outline", "chapters", "topics", "laws", "contents", "index", "list", "overview"));
            chunks.add(new Document(UUID.randomUUID().toString(), sectionMap.toString(), sectionMeta));
            log.info("SemanticChunkBuilder: generated SECTION_MAP with {} headings", headingBlocks.size());
        }

        // --- 2. Main semantic chunking pass ---
        // Active heading stack for sectionPath construction
        String[] headingStack = new String[4]; // index = level (1..3)
        Arrays.fill(headingStack, null);

        // Accumulation buffers
        List<DocumentBlock> accumulator = new ArrayList<>();
        List<DocumentBlock> tocEntries  = new ArrayList<>();
        int chunkIndex = 0;

        for (DocumentBlock block : blocks) {

            // Skip noise entirely
            if (block.isNoise()) continue;

            // TOC entries → collect separately
            if (block.type() == BlockType.TOC_ENTRY) {
                tocEntries.add(block);
                continue;
            }

            // Heading → flush accumulator, update heading stack, start new accumulator
            if (block.isHeading()) {
                if (!accumulator.isEmpty()) {
                    Document doc = flushAccumulator(accumulator, headingStack, chunkIndex++,
                            normalizedName, originalFileName, enrichedFileName,
                            sourceType, assetClassification, assetTags,
                            sessionId, sourceUrl, objectMapper);
                    if (doc != null) chunks.add(doc);
                    accumulator = new ArrayList<>();
                }

                // Update heading stack — clear deeper levels
                int level = block.headingLevel();
                headingStack[level] = block.text();
                for (int i = level + 1; i < headingStack.length; i++) headingStack[i] = null;

                // Start new accumulator with the heading itself
                accumulator.add(block);
                continue;
            }

            // Code block → its own isolated chunk
            if (block.type() == BlockType.CODE_BLOCK) {
                if (!accumulator.isEmpty()) {
                    Document doc = flushAccumulator(accumulator, headingStack, chunkIndex++,
                            normalizedName, originalFileName, enrichedFileName,
                            sourceType, assetClassification, assetTags,
                            sessionId, sourceUrl, objectMapper);
                    if (doc != null) chunks.add(doc);
                    accumulator = new ArrayList<>();
                }
                Map<String, Object> codeMeta = buildBaseMeta("CODE_BLOCK", normalizedName,
                        originalFileName, enrichedFileName, sourceType, assetClassification,
                        assetTags, sessionId, sourceUrl);
                codeMeta.put("chunkIndex", chunkIndex);
                enrichHeadingMeta(codeMeta, headingStack);
                chunks.add(new Document(UUID.randomUUID().toString(), block.text(), codeMeta));
                chunkIndex++;
                continue;
            }

            // Accumulated list: if current is list and we see a non-list block, flush
            boolean accumHasList = !accumulator.isEmpty() && accumulator.stream().anyMatch(DocumentBlock::isList);
            if (accumHasList && !block.isList()) {
                Document doc = flushAccumulator(accumulator, headingStack, chunkIndex++,
                        normalizedName, originalFileName, enrichedFileName,
                        sourceType, assetClassification, assetTags,
                        sessionId, sourceUrl, objectMapper);
                if (doc != null) chunks.add(doc);
                accumulator = new ArrayList<>();
            }

            // Size guard: flush if accumulator would exceed max chars
            int accumulatedChars = accumulator.stream().mapToInt(b -> b.text().length()).sum();
            if (!accumulator.isEmpty() && accumulatedChars + block.text().length() > MAX_SEMANTIC_CHARS) {
                Document doc = flushAccumulator(accumulator, headingStack, chunkIndex++,
                        normalizedName, originalFileName, enrichedFileName,
                        sourceType, assetClassification, assetTags,
                        sessionId, sourceUrl, objectMapper);
                if (doc != null) chunks.add(doc);
                accumulator = new ArrayList<>();
            }

            accumulator.add(block);
        }

        // Flush remaining accumulator
        if (!accumulator.isEmpty()) {
            Document doc = flushAccumulator(accumulator, headingStack, chunkIndex++,
                    normalizedName, originalFileName, enrichedFileName,
                    sourceType, assetClassification, assetTags,
                    sessionId, sourceUrl, objectMapper);
            if (doc != null) chunks.add(doc);
        }

        // Flush TOC entries as a single chunk
        if (!tocEntries.isEmpty()) {
            String tocText = "TABLE OF CONTENTS\n\n" +
                    tocEntries.stream().map(DocumentBlock::text).collect(Collectors.joining("\n"));
            Map<String, Object> tocMeta = buildBaseMeta("TOC", normalizedName, originalFileName,
                    enrichedFileName, sourceType, assetClassification, assetTags, sessionId, sourceUrl);
            tocMeta.put("chunkIndex", chunkIndex++);
            chunks.add(new Document(UUID.randomUUID().toString(), tocText, tocMeta));
            log.info("SemanticChunkBuilder: generated TOC chunk with {} entries", tocEntries.size());
        }

        // Stamp totalChunks on all chunks
        int total = chunks.size();
        for (Document doc : chunks) {
            doc.getMetadata().put("totalChunks", total);
        }

        log.info("SemanticChunkBuilder: {} blocks → {} semantic chunks", blocks.size(), chunks.size());
        return chunks;
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    private static Document flushAccumulator(
            List<DocumentBlock> blocks,
            String[] headingStack,
            int chunkIndex,
            String normalizedName,
            String originalFileName,
            String enrichedFileName,
            String sourceType,
            String assetClassification,
            String assetTags,
            Long sessionId,
            String sourceUrl,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper) {

        if (blocks.isEmpty()) return null;

        // Determine primary block type for the chunk (first non-heading type, or heading if all headings)
        BlockType primaryType = blocks.stream()
                .filter(b -> !b.isHeading())
                .map(DocumentBlock::type)
                .findFirst()
                .orElse(blocks.get(0).type());

        // Assemble text: heading gets its own line, rest concatenated
        StringBuilder sb = new StringBuilder();
        for (DocumentBlock b : blocks) {
            if (b.isHeading()) {
                sb.append(b.text()).append("\n\n");
            } else {
                sb.append(b.text()).append(" ");
            }
        }
        String text = sb.toString().trim();
        if (text.isEmpty()) return null;

        // Page from first block
        int page = blocks.get(0).pageNumber();
        int readingOrder = blocks.get(0).readingOrder();

        Map<String, Object> meta = buildBaseMeta(primaryType.name(), normalizedName,
                originalFileName, enrichedFileName, sourceType,
                assetClassification, assetTags, sessionId, sourceUrl);

        meta.put("chunkIndex", chunkIndex);
        meta.put("page", page);
        meta.put("readingOrder", readingOrder);
        if (chunkIndex > 0) meta.put("previousChunk", chunkIndex - 1);

        enrichHeadingMeta(meta, headingStack);

        // Bounding boxes (first block's bbox for simplicity)
        DocumentBlock first = blocks.get(0);
        meta.put("charStart", first.docCharStart());
        meta.put("charEnd", blocks.get(blocks.size() - 1).docCharEnd());

        return new Document(UUID.randomUUID().toString(), text, meta);
    }

    private static void enrichHeadingMeta(Map<String, Object> meta, String[] headingStack) {
        // Active heading = deepest non-null level
        String activeHeading = null;
        int activeLevel = 0;
        for (int i = headingStack.length - 1; i >= 1; i--) {
            if (headingStack[i] != null) {
                activeHeading = headingStack[i];
                activeLevel = i;
                break;
            }
        }

        // Parent heading = next-shallower non-null level
        String parentHeading = null;
        for (int i = activeLevel - 1; i >= 1; i--) {
            if (headingStack[i] != null) {
                parentHeading = headingStack[i];
                break;
            }
        }

        // Section path = all non-null levels joined
        String sectionPath = Arrays.stream(headingStack, 1, headingStack.length)
                .filter(Objects::nonNull)
                .collect(Collectors.joining(" > "));

        if (activeHeading != null)  meta.put("heading", activeHeading);
        if (activeLevel > 0)        meta.put("headingLevel", activeLevel);
        if (parentHeading != null)  meta.put("parentHeading", parentHeading);
        if (!sectionPath.isEmpty()) meta.put("sectionPath", sectionPath);
        meta.put("hierarchyDepth", activeLevel);
    }

    private static Map<String, Object> buildBaseMeta(
            String blockType, String normalizedName, String originalFileName,
            String enrichedFileName, String sourceType,
            String assetClassification, String assetTags,
            Long sessionId, String sourceUrl) {

        Map<String, Object> meta = new HashMap<>();
        meta.put("blockType", blockType);
        meta.put("sourceName", normalizedName);
        meta.put("originalFileName", originalFileName);
        meta.put("sourceType", sourceType);
        meta.put("sessionId", sessionId);
        if (enrichedFileName != null)       meta.put("enrichedFileName", enrichedFileName);
        if (assetClassification != null)    meta.put("assetClassification", assetClassification);
        if (assetTags != null)              meta.put("assetTags", assetTags);
        if (sourceUrl != null && !sourceUrl.isBlank()) meta.put("sourceUrl", sourceUrl);
        return meta;
    }
}
