package com.accenture.intern.docmind.repository;

import com.accenture.intern.docmind.entity.DocumentChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, Long> {

    /**
     * Lexical / keyword search across the entire company corpus — every chunk ever
     * uploaded by anyone, in any session — using Postgres's built-in full-text
     * search as our BM25-equivalent retriever. DocMind is a shared knowledge base,
     * so this intentionally does not filter by session; HybridRetrievalService
     * applies a same-session relevance *boost* afterwards rather than a hard scope
     * here.
     * <p>
     * - to_tsvector('english', content): tokenizes + stems the chunk text on the fly.
     *   (For larger corpora this should be promoted to a stored generated column with a
     *   GIN index — see the migration note on DocumentChunk — but computing it inline
     *   is correct and fine at this project's scale, and is backed by the GIN index
     *   created in FullTextSearchIndexInitializer.)
     * - plainto_tsquery('english', :query): turns the free-text user question into a
     *   tsquery, safely escaping operators so user input can never break the query.
     * - ts_rank_cd(...): Postgres's cover-density ranking function. It rewards term
     *   frequency and term proximity, which is the same signal BM25 is built around —
     *   close enough to BM25 in practice for chunk-level keyword retrieval without
     *   needing an external search engine like Elasticsearch/OpenSearch.
     */
    @Query(value = """
            SELECT * FROM document_chunks dc
            WHERE to_tsvector('english', dc.content) @@ plainto_tsquery('english', :query)
            ORDER BY ts_rank_cd(to_tsvector('english', dc.content), plainto_tsquery('english', :query)) DESC
            LIMIT :topK
            """, nativeQuery = true)
    List<DocumentChunk> keywordSearch(@Param("query") String query, @Param("topK") int topK);

    @Query(value = """
            SELECT * FROM document_chunks dc
            WHERE dc.source_name IN (:targetDocuments)
            AND to_tsvector('english', dc.content) @@ plainto_tsquery('english', :query)
            ORDER BY ts_rank_cd(to_tsvector('english', dc.content), plainto_tsquery('english', :query)) DESC
            LIMIT :topK
            """, nativeQuery = true)
    List<DocumentChunk> keywordSearchInSources(@Param("query") String query, @Param("topK") int topK, @Param("targetDocuments") List<String> targetDocuments);

    /**
     * Lexical / keyword search filtered specifically to chunks that are images.
     */
    @Query(value = """
            SELECT * FROM document_chunks dc
            WHERE dc.image_url IS NOT NULL 
              AND (CAST(:imageType AS text) IS NULL OR dc.image_type = CAST(:imageType AS text))
              AND to_tsvector('english', dc.content) @@ plainto_tsquery('english', :query)
            ORDER BY ts_rank_cd(to_tsvector('english', dc.content), plainto_tsquery('english', :query)) DESC
            LIMIT :topK
            """, nativeQuery = true)
    List<DocumentChunk> keywordSearchImages(@Param("query") String query, @Param("topK") int topK, @Param("imageType") String imageType);

    /**
     * Lexical / keyword search filtered to images AND required to match at least one metadata tag.
     */
    @Query(value = """
            SELECT * FROM document_chunks dc
            WHERE dc.image_url IS NOT NULL 
              AND (CAST(:imageType AS text) IS NULL OR dc.image_type = CAST(:imageType AS text))
              AND (
                  dc.entities && CAST(ARRAY[ :tags ] AS text[]) OR 
                  dc.topics && CAST(ARRAY[ :tags ] AS text[]) OR 
                  dc.objects && CAST(ARRAY[ :tags ] AS text[]) OR 
                  dc.technologies && CAST(ARRAY[ :tags ] AS text[]) OR 
                  dc.relationships && CAST(ARRAY[ :tags ] AS text[])
              )
              AND to_tsvector('english', dc.content) @@ plainto_tsquery('english', :query)
            ORDER BY ts_rank_cd(to_tsvector('english', dc.content), plainto_tsquery('english', :query)) DESC
            LIMIT :topK
            """, nativeQuery = true)
    List<DocumentChunk> keywordSearchImagesWithTags(@Param("query") String query, @Param("tags") List<String> tags, @Param("topK") int topK, @Param("imageType") String imageType);

    /**
     * Distinct source filenames across the whole company corpus. Used to detect
     * whether a query names one specific uploaded document (e.g. "tell about
     * hitesh" matching "Hitesh_Resume.pdf") so retrieval can switch to whole-
     * document mode for that one source instead of corpus-wide chunk ranking.
     */
    @Query("SELECT DISTINCT dc.sourceName FROM DocumentChunk dc WHERE dc.sourceName IS NOT NULL")
    List<String> findDistinctSourceNames();

    /**
     * Every chunk belonging to one exact source, in original document order, ignoring case.
     * Used by whole-document retrieval mode once a query has been matched to a
     * single source - we want full document coverage here, not a similarity-
     * ranked subset, so this intentionally bypasses keyword/dense ranking.
     */
    List<DocumentChunk> findBySourceNameIgnoreCaseOrderByChunkIndexAsc(String sourceName);

    /**
     * All chunks sharing the exact same section path within a given source document,
     * in original reading order. Used by CONTIGUOUS retrieval mode to expand an anchor
     * chunk into its full containing section. chunkIndex >= 0 excludes SECTION_MAP rows.
     */
    @Query("SELECT dc FROM DocumentChunk dc WHERE dc.sourceName = :sourceName " +
           "AND dc.sectionPath = :sectionPath AND dc.chunkIndex >= 0 " +
           "ORDER BY dc.chunkIndex ASC")
    List<DocumentChunk> findBySourceNameAndSectionPathOrderByChunkIndexAsc(
            @Param("sourceName") String sourceName,
            @Param("sectionPath") String sectionPath);

    /**
     * Retrieve specific chunks by their index. Used for neighbor context expansion.
     */
    @Query("SELECT dc FROM DocumentChunk dc WHERE dc.sourceName = :sourceName AND dc.chunkIndex IN :chunkIndices")
    List<DocumentChunk> findBySourceNameAndChunkIndexIn(@Param("sourceName") String sourceName, @Param("chunkIndices") List<Integer> chunkIndices);

    /**
     * Every chunk of an already-ingested document with this exact content hash,
     * if one exists anywhere in the corpus (empty list means this is new
     * content). Used to detect a duplicate re-upload and re-point those
     * existing chunks at the new session (so the new session gets the same
     * relevance boost it would've gotten from a fresh upload) instead of
     * re-chunking and re-embedding content that's already in the corpus.
     */
    List<DocumentChunk> findByContentHash(String contentHash);

    /**
     * Every chunk (text + any extracted-image sub-chunks) whose sourceUrl
     * matches a given Attachment's Cloudinary/source URL. Used by
     * AttachmentService#deleteExploreAttachment to find exactly which chunks
     * — and, via their vectorId, which Pinecone vectors — belong to a file
     * before hard-deleting it. Only meaningful once a chunk's sourceUrl is
     * IngestionJobPayload#sourceUrl / IngestionWorkerService).
     */
    List<DocumentChunk> findBySourceUrl(String sourceUrl);

    /**
     * Retrieve structural overview chunks (SECTION_MAP) where chunkIndex is -1.
     */
    @Query("SELECT dc FROM DocumentChunk dc WHERE dc.chunkIndex = -1 AND dc.sourceName IN :targetDocuments")
    List<DocumentChunk> findSectionMapsBySourceNames(@Param("targetDocuments") List<String> targetDocuments);

    @Query("SELECT dc FROM DocumentChunk dc WHERE dc.chunkIndex = -1 AND dc.sessionId = :sessionId")
    List<DocumentChunk> findSectionMapsBySession(@Param("sessionId") Long sessionId);

    /**
     * Fetch all image chunks for the given source names and optional section paths.
     */
    @Query("SELECT dc FROM DocumentChunk dc WHERE dc.sourceName IN :sourceNames " +
           "AND (COALESCE(:sectionPaths, NULL) IS NULL OR dc.sectionPath IN :sectionPaths) " +
           "AND dc.imageUrl IS NOT NULL")
    List<DocumentChunk> findImages(
        @Param("sourceNames") List<String> sourceNames, 
        @Param("sectionPaths") List<String> sectionPaths);
}
