package com.accenture.intern.docmind.aiservices.retrieval;

import io.pinecone.clients.Pinecone;
import org.openapitools.db_data.client.ApiClient;
import org.openapitools.db_data.client.api.VectorOperationsApi;
import org.openapitools.db_data.client.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
@org.springframework.context.annotation.Primary
public class IntegratedPineconeVectorStore implements VectorStore {

    private final VectorOperationsApi api;
    private final String namespace;
    private final String apiBasePath;
    private final String apiKey;

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();
    private static final java.net.http.HttpClient HTTP_CLIENT = java.net.http.HttpClient.newHttpClient();

    public IntegratedPineconeVectorStore(
            @Value("${spring.ai.vectorstore.pinecone.api-key}") String apiKey,
            @Value("${spring.ai.vectorstore.pinecone.index-name}") String indexName,
            @Value("${spring.ai.vectorstore.pinecone.namespace:default}") String namespace) {
        this.namespace = namespace;
        Pinecone pinecone = new Pinecone.Builder(apiKey).build();
        try {
            org.openapitools.db_control.client.model.IndexModel indexModel = pinecone.describeIndex(indexName);
            String host = indexModel.getHost();

            ApiClient apiClient = new ApiClient();
            apiClient.setApiKey(apiKey);
            apiClient.setBasePath("https://" + host);
            this.api = new VectorOperationsApi(apiClient);
            this.apiBasePath = "https://" + host;
            this.apiKey = apiKey;
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize IntegratedPineconeVectorStore", e);
        }
    }

    @Override
    public void add(List<Document> documents) {
        if (documents == null || documents.isEmpty())
            return;

        try {
            StringBuilder ndjson = new StringBuilder();

            for (Document doc : documents) {
                Map<String, Object> record = new HashMap<>();

                String id = doc.getId();
                if (id == null || id.isBlank()) {
                    id = UUID.randomUUID().toString();
                }

                record.put("_id", id);

                String text = doc.getText();
                if (text != null && text.length() > 32000) {
                    // Pinecone has a 40KB metadata limit per vector.
                    // The embedding model will truncate long text anyway,
                    // so we truncate here to prevent ingestion crashes on massive chunks (like huge
                    // Wikipedia tables).
                    text = text.substring(0, 32000);
                }
                record.put("text", text);

                for (Map.Entry<String, Object> entry : doc.getMetadata().entrySet()) {
                    record.put(entry.getKey(), entry.getValue());
                }

                ndjson.append(MAPPER.writeValueAsString(record)).append("\n");
            }

            java.net.http.HttpRequest httpRequest = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(this.apiBasePath + "/records/namespaces/" + namespace + "/upsert"))
                    .header("Api-Key", this.apiKey)
                    .header("Content-Type", "application/x-ndjson")
                    .header("X-Pinecone-Api-Version", "2025-04")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(ndjson.toString()))
                    .build();

            java.net.http.HttpResponse<String> response = HTTP_CLIENT.send(httpRequest,
                    java.net.http.HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 300) {
                throw new RuntimeException("Pinecone HTTP Error " + response.statusCode() + ": " + response.body());
            }

        } catch (Exception e) {
            throw new RuntimeException("Failed to upsert records to Pinecone", e);
        }
    }

    private static final int PINECONE_DELETE_BATCH_SIZE = 1000;

    @Override
    public void delete(List<String> idList) {
        // Pinecone enforces a max of 1000 IDs per request.
        try {
            for (int i = 0; i < idList.size(); i += PINECONE_DELETE_BATCH_SIZE) {
                List<String> batch = idList.subList(i, Math.min(i + PINECONE_DELETE_BATCH_SIZE, idList.size()));
                DeleteRequest deleteRequest = new DeleteRequest();
                deleteRequest.setIds(batch);
                deleteRequest.setNamespace(namespace);
                api.deleteVectors(deleteRequest);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete records from Pinecone", e);
        }
    }

    @Override
    public void delete(org.springframework.ai.vectorstore.filter.Filter.Expression filterExpression) {
        // Pinecone SearchRecords API currently does not support deleting by filter
        // expression natively in the Java SDK REST wrapper easily.
        // Or we could use the Control plane. Since this is an integrated store, we'll
        // throw unsupported for now.
        throw new UnsupportedOperationException(
                "Deleting by filter expression is not supported yet for IntegratedPineconeVectorStore.");
    }

    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        try {
            SearchRecordsRequestQuery query = new SearchRecordsRequestQuery();
            query.setInputs(Collections.singletonMap("text", request.getQuery()));

            int topK = request.getTopK() > 0 ? request.getTopK() : 15;
            query.setTopK(topK);

            if (request.getFilterExpression() != null) {
                org.springframework.ai.vectorstore.filter.converter.PineconeFilterExpressionConverter converter = new org.springframework.ai.vectorstore.filter.converter.PineconeFilterExpressionConverter();
                String filterJson = converter.convertExpression(request.getFilterExpression());
                log.info("Converted Pinecone JSON Filter: {}", filterJson);
                if (filterJson != null && !filterJson.isBlank()) {
                    try {
                        Map<String, Object> filterMap = MAPPER.readValue(filterJson,
                                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                                });
                        query.setFilter(filterMap);
                    } catch (Exception e) {
                        log.error("Failed to parse Pinecone filter JSON: {}", filterJson, e);
                    }
                }
            }

            SearchRecordsRequest searchRequest = new SearchRecordsRequest();
            searchRequest.setQuery(query);

            if (query.getFilter() != null) {
                log.info("Pinecone request metadata filter: {}", query.getFilter());
            }

            long pineconeStart = System.currentTimeMillis();
            SearchRecordsResponse response = api.searchRecordsNamespace(namespace, searchRequest);
            long duration = System.currentTimeMillis() - pineconeStart;
            log.info("[TIMING] Pinecone searchRecords API call (Integrated Inference): {}ms", duration);

            if (response.getResult() == null || response.getResult().getHits() == null) {
                return Collections.emptyList();
            }

            return response.getResult().getHits().stream()
                    .filter(hit -> hit.getScore() != null && hit.getScore() >= request.getSimilarityThreshold())
                    .map(hit -> {
                        Map<String, Object> fields = null;
                        if (hit.getFields() instanceof Map) {
                            fields = new HashMap<>((Map<String, Object>) hit.getFields());
                        } else if (hit.getAdditionalProperties() != null) {
                            fields = new HashMap<>(hit.getAdditionalProperties());
                        } else {
                            fields = new HashMap<>();
                        }

                        String text = "";
                        if (fields.containsKey("text")) {
                            text = String.valueOf(fields.remove("text"));
                        }

                        return new Document(hit.getId(), text, fields);
                    })
                    .collect(Collectors.toList());

        } catch (Exception e) {
            throw new RuntimeException("Failed to search records in Pinecone", e);
        }
    }

    @Override
    public List<Document> similaritySearch(String query) {
        return similaritySearch(SearchRequest.builder().query(query).build());
    }
}
