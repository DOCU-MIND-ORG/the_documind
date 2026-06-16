package com.accenture.intern.docmind.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.*;

public class SupabaseVectorStore implements VectorStore {

    private final JdbcTemplate jdbc;
    private final EmbeddingModel embeddingModel;
    private final ObjectMapper objectMapper = new ObjectMapper();


    private static final String INSERT_SQL = """
        INSERT INTO public.vector_store (id, content, metadata, embedding)
        VALUES (?::uuid, ?, CAST(? AS jsonb), ?::vector)
        ON CONFLICT (id) DO UPDATE
          SET content   = EXCLUDED.content,
              metadata  = EXCLUDED.metadata,
              embedding = EXCLUDED.embedding
        """;

    private static final String SEARCH_SQL = """
        SELECT id, content, metadata,
               1 - (embedding <=> ?::vector) AS similarity
        FROM public.vector_store
        ORDER BY embedding <=> ?::vector
        LIMIT ?
        """;

    public SupabaseVectorStore(JdbcTemplate jdbc, EmbeddingModel embeddingModel) {
        this.jdbc = jdbc;
        this.embeddingModel = embeddingModel;
    }

    @Override
    public void add(List<Document> documents) {
        for (Document doc : documents) {
            try {
                String text = doc.getText() != null ? doc.getText() : "";
                float[] floats = embeddingModel.embed(text);
                String metaJson = objectMapper.writeValueAsString(
                        doc.getMetadata() == null ? Collections.emptyMap() : doc.getMetadata()
                );
                String vector = toVectorString(floats);
                String id = (doc.getId() != null) ? doc.getId() : UUID.randomUUID().toString();

                jdbc.update(connection -> {
                    PreparedStatement ps = connection.prepareStatement(INSERT_SQL);
                    ps.setObject(1, UUID.fromString(id));
                    ps.setString(2, text);
                    ps.setObject(3, metaJson, Types.OTHER);
                    ps.setObject(4, vector, Types.OTHER);
                    return ps;
                });

            } catch (Exception e) {
                throw new RuntimeException("Failed to insert document: " + e.getMessage(), e);
            }
        }
    }

    // this should be used later for removing session wise uploaded documents if session is deleted
    @Override
    public void delete(List<String> idList) {
        for (String id : idList) {
            jdbc.update("DELETE FROM public.vector_store WHERE id = ?::uuid", id);
        }
    }
    @Override
    public void delete(Filter.Expression filterExpression) {

    }


    // Should think of any good model for similarity finding for now we are using cosine distance
    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        try {
            String text = request.getQuery();
            float[] queryEmbedding = embeddingModel.embed(text);
            String vector = toVectorString(queryEmbedding);
            double threshold = request.getSimilarityThreshold();

            return jdbc.query(connection -> {
                    PreparedStatement ps = connection.prepareStatement(SEARCH_SQL);
                    ps.setObject(1, vector, Types.OTHER);
                    ps.setObject(2, vector, Types.OTHER);
                    ps.setInt(3, request.getTopK());
                    return ps;
                },
                (rs, rowNum) -> {
                    double similarity = rs.getDouble("similarity");
                    if (similarity < threshold) return null;

                    String metaJson = rs.getString("metadata");
                    Map<String, Object> metadata = new HashMap<>();
                    try {
                        metadata = objectMapper.readValue(
                            metaJson,
                            new TypeReference<Map<String, Object>>() {}
                        );
                    } catch (Exception ignored) {}

                    return new Document(
                            rs.getString("id"),
                            rs.getString("content"),
                            metadata
                    );
                }
            ).stream().filter(Objects::nonNull).toList();

        } catch (Exception e) {
            throw new RuntimeException("Similarity search failed: " + e.getMessage(), e);
        }
    }

    private String toVectorString(float[] floats) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < floats.length; i++) {
            sb.append(floats[i]);
            if (i < floats.length - 1) sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }
}
