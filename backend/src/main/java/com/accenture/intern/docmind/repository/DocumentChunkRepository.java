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

    /**
     * Distinct source filenames across the whole company corpus. Used to detect
     * whether a query names one specific uploaded document (e.g. "tell about
     * hitesh" matching "Hitesh_Resume.pdf") so retrieval can switch to whole-
     * document mode for that one source instead of corpus-wide chunk ranking.
     */
    @Query("SELECT DISTINCT dc.sourceName FROM DocumentChunk dc WHERE dc.sourceName IS NOT NULL")
    List<String> findDistinctSourceNames();

    /**
     * Every chunk belonging to one exact source, in original document order.
     * Used by whole-document retrieval mode once a query has been matched to a
     * single source - we want full document coverage here, not a similarity-
     * ranked subset, so this intentionally bypasses keyword/dense ranking.
     */
    List<DocumentChunk> findBySourceNameOrderByChunkIndexAsc(String sourceName);

    /**
     * Every chunk of an already-ingested document with this exact content hash,
     * if one exists anywhere in the corpus (empty list means this is new
     * content). Used to detect a duplicate re-upload and re-point those
     * existing chunks at the new session (so the new session gets the same
     * relevance boost it would've gotten from a fresh upload) instead of
     * re-chunking and re-embedding content that's already in the corpus.
     */
    List<DocumentChunk> findByContentHash(String contentHash);
}
