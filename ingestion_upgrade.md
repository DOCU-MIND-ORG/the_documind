# Semantic Block-Based Chunking — Implementation Plan

## What This Replaces

The current pipeline in `EmbeddingService` has a **disconnected architecture**:

```
elements → detectHeadings() → headings list (only)
raw_text → chunkText() → character cursor loop → 1000-char chunks
```

The elements list and the raw text string are two parallel, disconnected pipelines. After heading detection, all element data (font, bbox, page, position) is thrown away and the chunker operates blindly on a character string.

**Target architecture:**

```
elements → BlockClassifier.classify() → typed blocks
         → SemanticChunkBuilder.build() → semantic chunks → Documents
```

Everything flows through elements. The raw text string is no longer the primary input to chunking.

---

## Files Changed

### [NEW] `BlockType.java`
**Location**: `embedding/` package  
A Java enum defining every document block type the classifier can produce:

```
TITLE, H1, H2, H3,
PARAGRAPH,
BULLET_LIST, NUMBERED_LIST,
TABLE,
FIGURE_CAPTION,
CODE_BLOCK,
HEADER, FOOTER, PAGE_NUMBER,
TOC_ENTRY,
UNKNOWN
```

---

### [NEW] `DocumentBlock.java`
**Location**: `embedding/` package  
A record (or simple class) holding a single classified block:

```java
record DocumentBlock(
    BlockType type,
    String text,
    int pageNumber,
    int docCharStart,
    int docCharEnd,
    float fontSize,
    String fontName,
    LayoutTextStripper.BoundingBox bbox,
    int headingLevel   // 0 if not a heading
)
```

---

### [NEW] `BlockClassifier.java`
**Location**: `embedding/` package  
The core logic. Takes `List<PdfTextElement>` and page height, returns `List<DocumentBlock>`.

**Scoring algorithm per element (multi-signal, same as discussed):**

| Signal | How | Points |
|---|---|---|
| Font > 140% median | fontSize | +3 |
| Font > 115% median | fontSize | +1 |
| Bold font | fontName contains `bold/heavy/black/demi` | +2 |
| Short text < 80 chars | text.length() | +1 |
| Very short < 40 chars | text.length() | +1 |
| Numbered prefix | regex `^\d+(\.\d+)*` or `^(Chapter\|Section\|Law)` | +3 |
| ALL CAPS and short | text.toUpperCase().equals(text) | +2 |
| Left-aligned (x < 15% of page width) | bbox.x() | +1 |

**Heading level from font size:**
- ≥ 90% of max font → H1
- ≥ 75% of max font → H2
- Otherwise → H3

**Header/Footer detection (relative, not hardcoded):**
- `bbox.y() < pageHeight * 0.05` → candidate `HEADER`
- `bbox.y() > pageHeight * 0.92` → candidate `FOOTER`
- If element text repeats on ≥ 3 pages → confirmed `HEADER` or `FOOTER` (will be excluded from chunks)

**List detection:**
- Text starts with `•`, `-`, `*`, `–` → `BULLET_LIST`
- Text starts with `1.`, `2.`, `a.`, `i.` pattern → `NUMBERED_LIST`

**TOC entry detection:**
- Text matches `Any text .... 123` (dots + page number at end) → `TOC_ENTRY`

---

### [NEW] `SemanticChunkBuilder.java`
**Location**: `embedding/` package  
Takes `List<DocumentBlock>` and builds semantic `Document` chunks.

**Chunk boundary rules:**

1. **Any heading block** (`H1`, `H2`, `H3`, `TITLE`) → **always** starts a new chunk (close previous, open new)
2. **List blocks** → accumulated together into a single list chunk, closed when a non-list block follows
3. **Paragraph blocks** → accumulated until `score > MAX_CHUNK_TOKENS (800)`, then split at paragraph boundary
4. `HEADER`, `FOOTER`, `PAGE_NUMBER` → **skipped entirely** (not added to any chunk)
5. `TOC_ENTRY` blocks grouped together → produce one `TOC` chunk

**Generated synthetic chunks (produced once per document):**
- **`SECTION_MAP`** chunk: Built from all `H1`/`H2` blocks, formatted as an indented outline. This is the chunk retrieved for "What chapters are in this document?"

**Metadata on every chunk:**

```json
{
  "blockType": "PARAGRAPH",
  "heading": "Chapter 4 — Attention",
  "headingLevel": 1,
  "sectionPath": "Part II > Chapter 4 — Attention",
  "page": 127,
  "chunkIndex": 42,
  "totalChunks": 198,
  "sessionId": "...",
  "sourceName": "...",
  "sourceType": "PDF"
}
```

---

### [MODIFY] `EmbeddingService.java`
**Specific change**: Replace the current `chunkText()` method body.

Instead of:
```java
detectHeadings(elements) → headings list
while (cursor < totalLen) { ... character cursor loop ... }
```

Replace with:
```java
List<DocumentBlock> blocks = BlockClassifier.classify(elements, pageHeights);
List<Document> chunks = SemanticChunkBuilder.build(blocks, metadata);
return chunks;
```

The `detectHeadings()` method and `DetectedHeading` record (added in the previous session) will be **deleted** — their functionality is absorbed into `BlockClassifier`.

The rest of `EmbeddingService` (`doIngest`, `saveChunksForKeywordSearch`, dedup logic, Pinecone/Postgres ingest) remains **completely unchanged**.

---

### [MODIFY] `LayoutTextStripper.java`
**One small addition**: Currently `writeString()` doesn't capture page height. We need to add page height capture so `BlockClassifier` can compute the 5% header/footer threshold correctly.

Add `pageHeight` to the `PdfTextElement` record or expose it separately via a `Map<Integer, Float> getPageHeights()` method.

---

## What Does NOT Change

| Component | Status |
|---|---|
| `HybridRetrievalService` | ✅ Unchanged |
| `RetrievalOrchestrator` | ✅ Unchanged |
| `PlannerService` | ✅ Unchanged |
| `RerankService` | ✅ Unchanged |
| `ContextBuilderService` | ✅ Unchanged |
| `DocumentChunkRepository` | ✅ Unchanged |
| `DocumentChunk` entity | ✅ Unchanged (heading/sectionPath columns already exist) |
| Pinecone/Postgres ingest logic | ✅ Unchanged |

---

## Verification Plan

1. Upload a structured PDF (book, research paper, or report with clear headings).
2. Check Postgres: every chunk should have `heading`, `sectionPath`, `blockType` populated.
3. Check that `HEADER`/`FOOTER` text (e.g., running page headers) is **absent** from all chunks.
4. Ask: *"What chapters are covered?"* → should retrieve the `SECTION_MAP` chunk and produce an accurate outline.
5. Ask: *"Explain backpropagation"* → should retrieve `PARAGRAPH` chunks from the correct section.
6. Compare chunk count before and after: semantic chunks should be more meaningful and fewer degenerate splits should appear.

---

## Open Questions for Your Review

> [!IMPORTANT]
> 1. **Page height**: Should I capture it in `LayoutTextStripper` per-page (cleanest), or compute it from the element bounding boxes post-hoc (no change to `LayoutTextStripper`)?
> 2. **Max chunk token limit**: I'll default to ~800 chars per accumulated paragraph group before splitting. Is that the right size given your Gemini context window?
> 3. **Overlap**: The current character chunker has a 200-char overlap between chunks. Semantic chunkers typically don't need overlap (because chunk boundaries are natural). Should I keep overlap (redundancy) or remove it (cleaner boundaries)?
