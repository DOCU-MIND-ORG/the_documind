package com.accenture.intern.docmind.ai;

import com.accenture.intern.docmind.service.VectorStoreService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class RagChatService {

    private final ChatClient chatClient;
    private final VectorStoreService vectorStoreService;

    // this System prompt should be updated later.
    private static final String SYSTEM_PROMPT = """
            You are a smart document assistant. You help users understand their uploaded documents.

            Your job:
            - Read the context chunks provided from the user's documents.
            - Then PERFORM whatever the user asks — summarize, explain, list, compare, extract, translate, simplify, or answer questions.
            - Follow the user's instruction exactly. If they say "summarize", give a summary. If they say "explain simply", use simple language. If they say "list key points", give bullet points.
            - Use ONLY information from the provided context. Never add outside knowledge.
            - Be concise and well-structured. Use bullet points, headings, or numbered lists when it helps clarity.
            - At the end, cite your sources like: [Source: <fileName>]
            - If the context does NOT contain enough information to answer, say exactly: "I couldn't find relevant information in the uploaded documents."
            """;

    public RagChatService(ChatClient.Builder chatClientBuilder,
                          VectorStoreService vectorStoreService) {
        this.chatClient        = chatClientBuilder.build();
        this.vectorStoreService = vectorStoreService;
    }

    // Present this is the one we are using for rag
    public RagResponse chatWithCitations(String question, String sessionId) {

        List<Document> docs = vectorStoreService.retrieve(question, 5);

        String contextBlock    = buildContextBlock(docs);
        String augmentedPrompt = buildPrompt(question, contextBlock);

        String answer = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(augmentedPrompt)
                .call()
                .content();

        List<Map<String, Object>> citations = docs.stream()
                .map(doc -> Map.<String, Object>of(
                        "sourceName",  doc.getMetadata().getOrDefault("sourceName",  "unknown"),
                        "sourceType",  doc.getMetadata().getOrDefault("sourceType",  ""),
                        "chunkIndex",  doc.getMetadata().getOrDefault("chunkIndex",  0),
                        "excerpt",     doc.getText().substring(0, Math.min(200, doc.getText().length())) + "…"
                ))
                .collect(Collectors.toList());

        log.info("RAG query='{}' sessionId={} chunks={}", question, sessionId, docs.size());
        return new RagResponse(answer, citations, true);
    }


    private String buildContextBlock(List<Document> docs) {
        if (docs.isEmpty()) return "No relevant documents found.";

        return docs.stream()
                .map(doc -> {
                    String name  = (String) doc.getMetadata().getOrDefault("sourceName", "unknown");
                    String type  = (String) doc.getMetadata().getOrDefault("sourceType", "");
                    int    chunk = ((Number) doc.getMetadata().getOrDefault("chunkIndex", 0)).intValue();
                    return "--- [Source: %s | Type: %s | Chunk: %d] ---\n%s"
                            .formatted(name, type, chunk, doc.getText());
                })
                .collect(Collectors.joining("\n\n"));
    }

    private String buildPrompt(String question, String context) {
        return """
                CONTEXT (from uploaded documents):
                %s

                ---

                TASK: %s
                """.formatted(context, question);
    }

    public record RagResponse(
            String answer,
            List<Map<String, Object>> citations,
            boolean foundInDocuments
    ) {}
}
