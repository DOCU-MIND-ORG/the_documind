package com.accenture.intern.docmind.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

/**
 * Hibernate's ddl-auto=update (see application.properties) creates the
 * document_chunks table and its columns, but it doesn't know how to create a GIN
 * full-text index, since that's Postgres-specific DDL with no JPA annotation
 * equivalent. We create it here, once, right after the app (and therefore
 * Hibernate's schema update) is ready.
 * <p>
 * CREATE INDEX IF NOT EXISTS makes this safe to run on every startup — it's a
 * no-op once the index already exists.
 * <p>
 * This keeps DocumentChunkRepository.keywordSearch() fast (GIN-indexed lookup
 * instead of a sequential scan + on-the-fly tsvector computation) without
 * introducing a migration framework like Flyway/Liquibase into the project.
 */
@Slf4j
@Component
public class FullTextSearchIndexInitializer {

    private final DataSource dataSource;

    public FullTextSearchIndexInitializer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ensureIndexExists() {
        String tsvIndexSql = """
                CREATE INDEX IF NOT EXISTS idx_document_chunks_content_tsv
                ON document_chunks
                USING GIN (to_tsvector('english', content))
                """;

        String sessionIndexSql = """
                CREATE INDEX IF NOT EXISTS idx_document_chunks_session_id
                ON document_chunks (session_id)
                """;

        String contentHashIndexSql = """
                CREATE INDEX IF NOT EXISTS idx_document_chunks_content_hash
                ON document_chunks (content_hash)
                """;

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(tsvIndexSql);
            stmt.execute(sessionIndexSql);
            stmt.execute(contentHashIndexSql);
            log.info("Verified indexes on document_chunks (full-text GIN + session_id + content_hash)");
        } catch (Exception e) {
            // Never fail startup over an index — keyword search just falls back to a
            // sequential scan (slower, but still correct) until this succeeds.
            log.warn("Could not create/verify indexes on document_chunks: {}", e.getMessage());
        }
    }
}
