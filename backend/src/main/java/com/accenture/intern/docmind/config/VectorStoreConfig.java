package com.accenture.intern.docmind.config;

import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class VectorStoreConfig {



    @Bean
    @org.springframework.context.annotation.Primary
    public org.springframework.ai.chat.model.ChatModel primaryChatModel(
            @org.springframework.beans.factory.annotation.Qualifier("googleGenAiChatModel") 
            org.springframework.ai.chat.model.ChatModel googleGenAiChatModel) {
        return googleGenAiChatModel;
    }
}
