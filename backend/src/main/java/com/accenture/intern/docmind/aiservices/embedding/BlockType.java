package com.accenture.intern.docmind.aiservices.embedding;

/**
 * Every logical block type that the BlockClassifier can assign to a LayoutBlock.
 *
 * TABLE and FIGURE_CAPTION are declared but intentionally not produced by the
 * current classifier implementation — they are placeholders for future detection
 * logic (table spatial-clustering and caption-to-figure association) so the
 * chunk metadata schema does not need to change when those features are added.
 */
public enum BlockType {

    /** The document title — typically the single largest text on the first page. */
    TITLE,

    /** Top-level chapter heading (H1 equivalent). */
    H1,

    /** Section heading (H2 equivalent). */
    H2,

    /** Sub-section heading (H3 equivalent). */
    H3,

    /** Normal body paragraph. */
    PARAGRAPH,

    /** Bulleted (unordered) list item group. */
    BULLET_LIST,

    /** Numbered (ordered) list item group. */
    NUMBERED_LIST,

    /**
     * Table block.
     * NOTE: Not produced by the current implementation.
     * Reserved for future table-detection logic.
     */
    TABLE,

    /**
     * Figure or image caption.
     * NOTE: Not produced by the current implementation.
     * Reserved for future caption-to-figure association logic.
     */
    FIGURE_CAPTION,

    /** Code snippet or pre-formatted block (detected via monospace font). */
    CODE_BLOCK,

    /**
     * Running page header — appears at the top of most pages.
     * Excluded from all chunks during SemanticChunkBuilder processing.
     */
    HEADER,

    /**
     * Running page footer — appears at the bottom of most pages.
     * Excluded from all chunks during SemanticChunkBuilder processing.
     */
    FOOTER,

    /** Standalone page number. Excluded from all chunks. */
    PAGE_NUMBER,

    /**
     * An entry in the document's own Table of Contents
     * (e.g., "Chapter 3 ......... 42").
     * Grouped into a single TOC chunk during SemanticChunkBuilder processing.
     */
    TOC_ENTRY,

    /** Could not be classified with sufficient confidence. Treated as PARAGRAPH. */
    UNKNOWN
}
