package com.accenture.intern.docmind.aiservices.understanding;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import com.accenture.intern.docmind.entity.ModelName;
import com.accenture.intern.docmind.dto.context.SessionContext;
import com.accenture.intern.docmind.dto.context.DocumentReference;

import java.util.Collections;
import java.util.List;

import com.accenture.intern.docmind.aiservices.understanding.plan.StaticExecutionPlan;

@Slf4j
@Service
public class QueryDecomposer {

    private final ChatClient chatClient;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public QueryDecomposer(ChatClient.Builder chatClientBuilder, com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.chatClient = chatClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    public Mono<StaticExecutionPlan> decompose(String originalQuery, String history, SessionContext sessionContext) {
        List<String> uploadedDocs = sessionContext != null && sessionContext.uploadedDocuments() != null 
                ? sessionContext.uploadedDocuments().stream().map(DocumentReference::filename).toList() 
                : Collections.emptyList();
        
        String activeDocsStr = uploadedDocs != null && !uploadedDocs.isEmpty() 
                ? String.join("\n- ", uploadedDocs) 
                : "None";

        String prompt = """
                You are an expert query decomposer for an enterprise RAG system.
                The user has submitted a complex query that requires decomposing into multiple, parallel retrieval plans.
                
                ═══════════════════════════════════════════════════════════════════
                SESSION STATE
                ═══════════════════════════════════════════════════════════════════
                Active documents in this session:
                - %s

                ═══════════════════════════════════════════════════════════════════
                YOUR TASK
                ═══════════════════════════════════════════════════════════════════
                Break down the query into a list of "plans". For example, if the query asks to compare "Hitesh" and "Tejesh",
                you must output two plans: one to retrieve information about Hitesh, and one for Tejesh.

                CRITICAL ENTITY SCOPING RULE:
                You MUST assign only the specific entities relevant to a sub-plan into its `entities` array. 
                Do not copy all entities from the global query into every sub-plan.
                Example: Plan 1 (Hitesh) gets entities ["Hitesh"]. Plan 2 (Tejesh) gets entities ["Tejesh"].

                Maximum Plans allowed: 3. Do not generate more than 3 plans.
                
                Also select a `mergeOperation` from the following:
                - COMPARE: The results should be shown side-by-side (e.g. comparing two people, two companies).
                - UNION: The results are structurally merged and deduplicated (e.g. find all projects by X and Y).
                - NONE: Just fetch the results without special formatting.

                OUTPUT — Return ONLY a valid JSON object matching this structure:
                {
                  "mergeOperation": "COMPARE | UNION | NONE",
                  "plans": [
                    {
                      "purpose": "A brief, human-readable string explaining what this sub-plan is retrieving (e.g. 'Retrieve Hitesh JEE Rank')",
                      "optimizedQuery": "Keyword-dense specific query for this plan",
                      "entities": ["entity1"],
                      "strategy": "SINGLE_SOURCE | META_DOC_SEARCH",
                      "scope": "CORPUS | SPECIFIC_DOC | NONE"
                    }
                  ]
                }
                
                ═══════════════════════════════════════════════════════════════════
                CONVERSATION HISTORY
                ═══════════════════════════════════════════════════════════════════
                %s

                User Query: "%s"
                """
                .formatted(activeDocsStr, history != null ? history : "No prior history.", originalQuery);

        GoogleGenAiChatOptions options = new GoogleGenAiChatOptions();
        options.setModel(ModelName.GEMINI_3_1_FLASH_LITE.getModelString());
        options.setTemperature(0.0);

        long t0 = System.currentTimeMillis();
        return Mono.fromCallable(() -> {
                    String rawResponse = chatClient.prompt().user(prompt).options(options).call().content();
                    try {
                        String jsonToParse = rawResponse;
                        if (rawResponse != null) {
                            int start = rawResponse.indexOf("{");
                            int end = rawResponse.lastIndexOf("}");
                            if (start != -1 && end != -1 && end > start) {
                                jsonToParse = rawResponse.substring(start, end + 1);
                            }
                        }
                        
                        DecomposerResponse response = objectMapper.readValue(jsonToParse, DecomposerResponse.class);
                        
                        List<RetrievalPlan> plans = response.plans().stream()
                            .limit(3) // Hard cap at 3 plans
                            .map(p -> new RetrievalPlan(
                                    java.util.UUID.randomUUID().toString(),
                                    p.purpose() != null ? p.purpose() : "Decomposed Plan",
                                    Intent.DOCUMENT_QA, // Decomposed plans are almost always QA
                                    p.scope() != null ? Scope.valueOf(p.scope()) : Scope.CORPUS,
                                    p.strategy() != null ? RetrievalStrategy.valueOf(p.strategy()) : RetrievalStrategy.SINGLE_SOURCE,
                                    RetrievalExecutionMode.RANKED_RETRIEVAL,
                                    List.of(), List.of(),
                                    p.optimizedQuery(),
                                    p.entities() != null ? p.entities().stream().map(e -> new EntityResolution(e, 1.0)).toList() : List.of(),
                                    List.of()
                            )).toList();
                            
                        MergeOperation mergeOp = MergeOperation.NONE;
                        if (response.mergeOperation() != null) {
                            try {
                                mergeOp = MergeOperation.valueOf(response.mergeOperation().toUpperCase());
                            } catch (Exception e) {
                                log.warn("Invalid merge operation: {}. Defaulting to NONE", response.mergeOperation());
                            }
                        }
                        
                        return new StaticExecutionPlan(plans, mergeOp);
                        
                    } catch (Exception e) {
                        log.error("Failed to parse QueryDecomposer response. Raw: " + rawResponse, e);
                        // Fallback to a single plan if decomposition fails
                        RetrievalPlan fallback = new RetrievalPlan(Intent.DOCUMENT_QA, Scope.CORPUS, RetrievalStrategy.SINGLE_SOURCE, RetrievalExecutionMode.RANKED_RETRIEVAL, List.of(), List.of(), originalQuery, List.of(), List.of());
                        return new StaticExecutionPlan(List.of(fallback), MergeOperation.NONE);
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(res -> log.info("[TIMING] QueryDecomposer: {}ms | plans={} | mergeOperation={}", System.currentTimeMillis() - t0, res.plans().size(), res.mergeOperation()));
    }
    
    public record DecomposerResponse(
        String mergeOperation,
        List<DecomposerPlan> plans
    ) {}
    
    public record DecomposerPlan(
        String purpose,
        String optimizedQuery,
        List<String> entities,
        String strategy,
        String scope
    ) {}
}
