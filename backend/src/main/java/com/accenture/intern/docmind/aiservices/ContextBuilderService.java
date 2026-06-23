package com.accenture.intern.docmind.aiservices;

import com.accenture.intern.docmind.dto.chat.ContextResult;
import com.accenture.intern.docmind.dto.chat.EmbeddedDocument;
import com.accenture.intern.docmind.dto.chat.SessionUploadState;
import com.accenture.intern.docmind.dto.chat.UploadState;
import lombok.extern.slf4j.Slf4j;
import com.accenture.intern.docmind.service.SessionCacheService;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ContextBuilderService {

    private final VectorStoreService vectorStoreService;
    private final SessionCacheService sessionCacheService;
    private final RerankService rerankService;
    private final String ragPrompt;
    private final String generalPrompt;

    public ContextBuilderService(
            VectorStoreService vectorStoreService,
            SessionCacheService sessionCacheService,
            RerankService rerankService,
            @Value("classpath:prompts/ragprompt.st") Resource ragPromptResource,
            @Value("classpath:prompts/generalprompt.st") Resource generalPromptResource) throws IOException {
        this.vectorStoreService = vectorStoreService;
        this.sessionCacheService = sessionCacheService;
        this.rerankService = rerankService;
        this.ragPrompt = StreamUtils.copyToString(ragPromptResource.getInputStream(), StandardCharsets.UTF_8);
        this.generalPrompt = StreamUtils.copyToString(generalPromptResource.getInputStream(), StandardCharsets.UTF_8);
    }

    public reactor.core.publisher.Mono<ContextResult> buildContext(String question, Long sessionId) {
        SessionUploadState state = sessionCacheService.getState(sessionId);

        if (state != null && (state.getState() == UploadState.EMBEDDING || state.getState() == UploadState.INGESTING)) {
            List<EmbeddedDocument> embeddedDocs = state.getEmbeddedDocuments();
            if (embeddedDocs == null || embeddedDocs.isEmpty()) {
                return fallbackRetrieve(question);
            }

            boolean isGeneralQuestion = isGeneralGreetingOrInquiry(question);
            if (isGeneralQuestion) {
                return fallbackRetrieve(question);
            }

            int estimatedTokens = embeddedDocs.stream()
                    .mapToInt(ed -> ed.getDocument().getText().length() / 4)
                    .sum();

            // Since we no longer generate local embeddings, we pass all chunks from the temporary cache.
            // Gemini handles large context windows easily.
            List<Document> selectedDocs = embeddedDocs.stream()
                    .map(EmbeddedDocument::getDocument)
                    .toList();
            log.info("Using {} local chunks ({} tokens) directly from temporary cache", selectedDocs.size(), estimatedTokens);

            return reactor.core.publisher.Mono.just(new ContextResult(
                    selectedDocs.isEmpty() ? generalPrompt : ragPrompt,
                    buildPrompt(question, buildContextBlock(selectedDocs)),
                    selectedDocs
            ));
        }

        return fallbackRetrieve(question);
    }

    private reactor.core.publisher.Mono<ContextResult> fallbackRetrieve(String question) {
        // Retrieve 15 docs for high recall, then rerank down to 5
        return vectorStoreService.retrieve(question, 15)
                .map(docs -> {
                    List<Document> rerankedDocs = rerankService.rerank(question, docs, 5);
                    String contextBlock = buildContextBlock(rerankedDocs);
                    String augmentedPrompt = buildPrompt(question, contextBlock);
                    String systemPrompt = rerankedDocs.isEmpty() ? generalPrompt : ragPrompt;
                    return new ContextResult(systemPrompt, augmentedPrompt, rerankedDocs);
                });
    }

    private boolean isGeneralGreetingOrInquiry(String question) {
        String q = question.toLowerCase();
        return q.matches("^(hi|hello|hey|greetings|how are you|what can you do).*");
    }

    private String buildContextBlock(List<Document> docs) {
        if (docs.isEmpty()) {
            return "No relevant documents found.";
        }

        StringBuilder sb = new StringBuilder();
        int index = 1;
        for (Document doc : docs) {
            String name = (String) doc.getMetadata().getOrDefault("sourceName", "unknown");
            String type = (String) doc.getMetadata().getOrDefault("sourceType", "");
            int chunk = ((Number) doc.getMetadata().getOrDefault("chunkIndex", 0)).intValue();
            
            sb.append(String.format("<CITATION id=\"%d\">\nSource: %s | Type: %s | Chunk: %d\n%s\n</CITATION>\n\n",
                    index++, name, type, chunk, doc.getText()));
        }
        return sb.toString().trim();
    }

    private String buildPrompt(String question, String context) {
        return """
                CONTEXT (from uploaded documents):
                %s

                ---

                TASK: %s
                """.formatted(context, question);
    }
}
