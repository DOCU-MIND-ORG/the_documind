package com.accenture.intern.docmind.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pinecone.PineconeVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class VectorStoreConfig {

    @Value("${pinecone.api-key}")
    private String pineconeApiKey;

    @Value("${pinecone.index-name}")
    private String pineconeIndexName;

    @Value("${pinecone.namespace:default}")
    private String pineconeNamespace;

    @Bean
    public VectorStore vectorStore(EmbeddingModel embeddingModel) {
        return PineconeVectorStore.builder(embeddingModel)
                .apiKey(pineconeApiKey)
                .indexName(pineconeIndexName)
                .namespace(pineconeNamespace)
                .build();
    }
}
