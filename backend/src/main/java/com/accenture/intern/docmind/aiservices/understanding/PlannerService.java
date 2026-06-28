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
import com.accenture.intern.docmind.aiservices.fastintent.FastIntentService;
import com.accenture.intern.docmind.aiservices.understanding.plan.ExecutionPlan;
import com.accenture.intern.docmind.aiservices.understanding.plan.DirectExecutionPlan;
import com.accenture.intern.docmind.aiservices.understanding.plan.StaticExecutionPlan;
import com.accenture.intern.docmind.aiservices.understanding.plan.AdaptiveExecutionPlan;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class PlannerService {

    private final ChatClient chatClient;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final FastIntentService fastIntentService;
    private final QueryDecomposer queryDecomposer;

    private static final Set<String> DEICTIC_WORDS = Set.of(
            "this", "that", "it", "given", "uploaded", "attached", "above", "provided");

    public PlannerService(ChatClient.Builder chatClientBuilder, com.fasterxml.jackson.databind.ObjectMapper objectMapper, FastIntentService fastIntentService, QueryDecomposer queryDecomposer) {
        this.chatClient = chatClientBuilder.build();
        this.objectMapper = objectMapper;
        this.fastIntentService = fastIntentService;
        this.queryDecomposer = queryDecomposer;
    }

    public Mono<ExecutionPlan> routeQuery(String query, String history, SessionContext sessionContext, reactor.core.publisher.Sinks.Many<org.springframework.http.codec.ServerSentEvent<String>> progressSink) {
        String cleanQuery = query.toLowerCase().trim();

        Intent fastIntent = fastIntentService.classifyIntent(cleanQuery);
        if (fastIntent != null) {
            log.info("ROUTER: Fast-pass triggered: {}", fastIntent);
            return Mono.just(new DirectExecutionPlan(new RetrievalPlan(fastIntent, Scope.NONE)));
        }

        return runUnifiedLlmRouter(cleanQuery, query, history, sessionContext, progressSink)
                .flatMap(response -> {
                    String tier = response.executionTier() != null ? response.executionTier().toUpperCase() : "DIRECT";
                    
                    if ("ADAPTIVE".equals(tier)) {
                        log.info("ROUTER: Adaptive execution triggered for query: {}", query);
                        List<String> expectedEntities = response.entities() != null ? response.entities().stream().map(com.accenture.intern.docmind.aiservices.understanding.EntityResolution::canonicalEntity).toList() : Collections.emptyList();
                        return Mono.just(new AdaptiveExecutionPlan("Adaptive retrieval for: " + query, expectedEntities, 1, 3));
                    }
                    
                    if ("DECOMPOSE".equals(tier)) {
                        log.info("ROUTER: Needs decomposition triggered for query: {}", query);
                        return queryDecomposer.decompose(query, history, sessionContext)
                            .map(execPlan -> {
                                if (execPlan.plans().size() <= 1) {
                                    log.warn("ROUTER: Decomposer returned {} plans despite execution_tier=DECOMPOSE. Proceeding normally.", execPlan.plans().size());
                                }
                                return execPlan;
                            });
                    }
                    
                    List<String> activeDocumentNames = sessionContext != null && sessionContext.uploadedDocuments() != null 
                        ? sessionContext.uploadedDocuments().stream().map(DocumentReference::filename).toList() 
                        : Collections.emptyList();
                        
                    RetrievalPlan decision = buildRetrievalPlan(response, cleanQuery, query, activeDocumentNames);
                    
                    log.info("\n=== Query Understanding ===\n" +
                            "Original Query       : {}\n" +
                            "Optimized Query      : {}\n" +
                            "Intent               : {}\n" +
                            "Strategy             : {}\n" +
                            "Scope                : {}\n" +
                            "Entities             : {}\n" +
                            "Referenced Documents : {}\n" +
                            "===========================",
                            query,
                            decision.optimizedQuery(),
                            decision.intent(),
                            decision.retrievalStrategy(),
                            decision.scope(),
                            decision.entities(),
                            decision.targetDocuments());
                    
                    return Mono.just(new DirectExecutionPlan(decision));
                });
    }

    private Mono<LlmRoutingResponse> runUnifiedLlmRouter(String cleanQuery, String originalQuery,
                    String history, SessionContext sessionContext, reactor.core.publisher.Sinks.Many<org.springframework.http.codec.ServerSentEvent<String>> progressSink) {

        return callUnifiedRouter(originalQuery, history, sessionContext)
                .doOnNext(response -> {
                    if (progressSink != null) {
                        String tier = response.executionTier() != null ? response.executionTier() : "DIRECT";
                        String msg = new com.accenture.intern.docmind.dto.chat.ProgressEvent(
                            com.accenture.intern.docmind.dto.chat.ProgressStage.PLANNING,
                            com.accenture.intern.docmind.dto.chat.ProgressStatus.COMPLETE,
                            "Planning retrieval...", null, null, java.util.Map.of("tier", tier)
                        ).toJson(objectMapper);
                        progressSink.tryEmitNext(org.springframework.http.codec.ServerSentEvent.<String>builder(msg).event("progress").build());
                    }
                });
    }

    private Mono<LlmRoutingResponse> callUnifiedRouter(String query, String history, SessionContext sessionContext) {
        List<String> uploadedDocs = sessionContext != null && sessionContext.uploadedDocuments() != null 
                ? sessionContext.uploadedDocuments().stream().map(DocumentReference::filename).toList() 
                : Collections.emptyList();
        java.util.Map<String, String> aliases = sessionContext != null ? sessionContext.aliases() : Collections.emptyMap();
        
        String activeDocsStr = uploadedDocs != null && !uploadedDocs.isEmpty() 
                ? String.join("\n- ", uploadedDocs) 
                : "None";

        StringBuilder aliasesStr = new StringBuilder();
        if (aliases != null && !aliases.isEmpty()) {
                aliases.forEach((alias, filename) -> aliasesStr.append("- ").append(alias).append(" -> ").append(filename).append("\n"));
        } else {
                aliasesStr.append("None\n");
        }

        String prompt = """
        You are an expert search query analyzer and optimizer for an enterprise RAG system.
        Your job is to SIMULTANEOUSLY classify the retrieval strategy AND produce a keyword-rich
        expanded query in a single response. This is a hot-path call — be concise and precise.
 
        ═══════════════════════════════════════════════════════════════════
        SESSION STATE
        ═══════════════════════════════════════════════════════════════════
        The user has uploaded the following active documents in this session:
        - %s
 
        Available aliases for deterministic matching:
        %s
        IMPORTANT: If the user uses references like "the first one", "all of them", "the astronomy article",
        "the latest upload", or "these three", resolve them strictly to the exact filenames from the list above using the aliases map.
        Populate the "target_documents" JSON array with the exact filenames you resolved.
        If no specific document is requested, leave "target_documents" empty.
 
        ═══════════════════════════════════════════════════════════════════
        PART A0 — BOT IDENTITY / CAPABILITY CHECK
        ═══════════════════════════════════════════════════════════════════
        First, decide whether this query is actually about the assistant itself —
        its capabilities, what it can help with, or who/what it is — rather than
        about the content of any uploaded document. This covers ANY phrasing of
        that idea, not just exact matches: "what can you do", "what all can you
        help with", "what are you able to do", "who are you", "what is this tool",
        "what kind of questions can I ask you", etc.
        → If true, set "is_bot_qa": true. No document retrieval will run for this
          query, so the rest of this prompt (strategy/entities/target_documents)
          can be left at their defaults — they are ignored when is_bot_qa is true.
        → If the query asks about document content, summarization, extraction, or
          anything that needs the uploaded corpus, set "is_bot_qa": false.

        ═══════════════════════════════════════════════════════════════════
        PART A — STRATEGY CLASSIFICATION
        ═══════════════════════════════════════════════════════════════════
 
        Classify into EXACTLY ONE of these strategies:
 
        1. MULTI_SOURCE
           Triggered when the user wants to compare, contrast, or find similarities/differences
           between named entities, AND each entity only needs ONE independent retrieval pass,
           AND the only fusion step is presenting the results side-by-side.
           → Populate "comparisons" array with one entry per entity.
           → Do NOT set needs_decomposition for this case.
 
        2. CONCEPT_EXPANSION
           Triggered when the query is about a SINGLE abstract/implicit topic with no obvious
           keyword match in typical document text (e.g. "resilience", "grief", "innovation").
           → Generate 3-4 dense, action-oriented sub-queries expanding the concept.
 
        3. META_DOC_SEARCH
           Triggered when the user asks for a semantic corpus search ABOUT their document collection
           (e.g. "Which uploaded documents discuss black holes?", "Name files that talk about gravity?").
           → Leave both "comparisons" and "sub_queries" empty.
 
        4. SINGLE_SOURCE
           Default. Standard factual lookup targeting a single concrete topic in one document.
           → Leave both "comparisons" and "sub_queries" empty.
 
        ═══════════════════════════════════════════════════════════════════
        PART B — UNIVERSAL QUERY EXPANSION
        ═══════════════════════════════════════════════════════════════════
 
        Always populate "optimized_query" with a keyword-dense rewrite of the user's query.
        Rules:
        - If the query is already keyword-rich and concrete, return it UNCHANGED.
        - If the query contains abstract concepts, EXPAND them into concrete keywords.
        - CRITICAL: If the query contains conversational references (e.g. "this roadmap", "that document", "it"), you MUST use the CONVERSATION HISTORY below to resolve the reference to its concrete noun/filename, and rewrite the query to explicitly include it (e.g. "tell me more about the DSA roadmap in screenshot.png").
 
        ═══════════════════════════════════════════════════════════════════
        PART C — COMPLEX QUERY DECOMPOSITION & EXECUTION TIER
        ═══════════════════════════════════════════════════════════════════
        Set "execution_tier" to one of:
        - "DIRECT": The query can be resolved in a single step (e.g., "What is X?", "Compare X and Y").
        - "DECOMPOSE": The query requires 3+ entities to be compared in parallel.
        - "ADAPTIVE": The query explicitly requires a goal-oriented, multi-hop search loop. This is REQUIRED when the answer depends on finding an unknown entity based on the characteristics of a known entity (e.g., "Which Avengers have leadership similar to Captain America?", "Does our PTO policy comply with the Labor Law?", "Which server has the same error as Database A?"). In these cases, you must retrieve the characteristics of the known entity FIRST, observe the results, and then formulate a second search for the unknown entity.

        If "execution_tier" is DECOMPOSE or ADAPTIVE, you DO NOT need to populate "comparisons" or "sub_queries".

        ═══════════════════════════════════════════════════════════════════
        PART D — ENTITY EXTRACTION WITH CONFIDENCE
        ═══════════════════════════════════════════════════════════════════
        For each entity you identify, also estimate your confidence (0.0–1.0) that it is the
        correct resolution of the user's reference. Be conservative:
        - High confidence (0.85+): the entity is explicitly named, or there is exactly one
          plausible referent given the session state and conversation history.
        - Medium confidence (0.5–0.84): the reference is resolvable but relies on inferring from
          context (e.g. "my" with one active user document).
        - Low confidence (<0.5): multiple plausible referents exist (e.g. multiple users'
          documents are active and "my" could mean more than one), or the reference is genuinely
          ambiguous. Do NOT silently default to the most likely guess at full confidence — report
          the true uncertainty so downstream ranking does not over-trust a guess.
 
        ═══════════════════════════════════════════════════════════════════
        OUTPUT — Return ONLY a valid JSON object, no explanation:
        ═══════════════════════════════════════════════════════════════════
        {
          "is_bot_qa": false,
          "strategy": "SINGLE_SOURCE | MULTI_SOURCE | CONCEPT_EXPANSION | META_DOC_SEARCH",
          "execution_tier": "DIRECT | DECOMPOSE | ADAPTIVE",
          "optimized_query": "keyword-dense rewrite",
          "entities": [ { "name": "resolved canonical entity name", "confidence": 0.0 } ],
          "comparisons": [ { "entity": "clean name", "optimized_query": "name + expanded keywords" } ],
          "sub_queries": ["for CONCEPT_EXPANSION"],
          "target_documents": ["resolved exact filenames"]
        }
        ═══════════════════════════════════════════════════════════════════
        CONVERSATION HISTORY
        ═══════════════════════════════════════════════════════════════════
        %s
 
        User Query: "%s"
        """
                .formatted(activeDocsStr, aliasesStr.toString(), history != null ? history : "No prior history.", query);

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
                                return objectMapper.readValue(jsonToParse, LlmRoutingResponse.class);
                        } catch (Exception e) {
                                throw new RuntimeException("Parse failed. Raw: " + rawResponse, e);
                        }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(res -> log.info("[TIMING] Unified LLM Router: {}ms | strategy={} | execution_tier={}", System.currentTimeMillis() - t0, res.strategy(), res.executionTier()))
                .retry(1)
                .onErrorResume(e -> {
                        String fallbackStrategy = "SINGLE_SOURCE";
                        log.error("Unified LLM Router failed. Falling back to {} using heuristics. Error: {}", fallbackStrategy, e.getMessage());
                        return Mono.just(new LlmRoutingResponse(fallbackStrategy, false, query, List.of(), List.of(), List.of(), List.of(), "DIRECT"));
                });
    }

    private RetrievalPlan buildRetrievalPlan(LlmRoutingResponse response, String cleanQuery, String originalQuery, List<String> activeDocumentNames) {
        String optimizedQuery = (response.optimizedQuery() != null && !response.optimizedQuery().isBlank()) ? response.optimizedQuery() : originalQuery;
        String strategyStr = response.strategy() != null ? response.strategy() : "SINGLE_SOURCE";
        
        List<EntityResolution> resolvedEntities = response.entities() != null ? response.entities() : List.of();

        // The LLM router's own judgment that this query is about the bot's
        // capabilities/identity rather than document content - catches phrasings
        // FastIntentService's fixed keyword list doesn't recognize (e.g. "what all
        // can you help with"), instead of those silently falling through to a
        // DOCUMENT_QA retrieval plan and forcing a citation-mandatory answer onto
        // a question that has nothing to do with any uploaded document.
        if (Boolean.TRUE.equals(response.isBotQa())) {
            return new RetrievalPlan(Intent.BOT_QA, Scope.NONE, RetrievalStrategy.SINGLE_SOURCE, RetrievalExecutionMode.WHOLE_DOCUMENT, List.of(), List.of(), optimizedQuery, resolvedEntities, List.of());
        }

        if ("MULTI_SOURCE".equals(strategyStr) && response.comparisons() != null && response.comparisons().size() >= 2) {
            return new RetrievalPlan(Intent.DOCUMENT_QA, Scope.CORPUS, RetrievalStrategy.MULTI_SOURCE, RetrievalExecutionMode.RANKED_RETRIEVAL, response.comparisons(), List.of(), optimizedQuery, resolvedEntities, response.targetDocuments() != null ? response.targetDocuments() : List.of());
        }

        if ("META_DOC_SEARCH".equals(strategyStr)) {
            return new RetrievalPlan(Intent.DOCUMENT_QA, Scope.CORPUS, RetrievalStrategy.META_DOC_SEARCH, RetrievalExecutionMode.RANKED_RETRIEVAL, List.of(), List.of(), optimizedQuery, resolvedEntities, response.targetDocuments() != null ? response.targetDocuments() : List.of());
        }

        if ("CONCEPT_EXPANSION".equals(strategyStr) && response.subQueries() != null && !response.subQueries().isEmpty()) {
            return new RetrievalPlan(Intent.DOCUMENT_QA, Scope.CORPUS, RetrievalStrategy.CONCEPT_EXPANSION, RetrievalExecutionMode.RANKED_RETRIEVAL, List.of(), response.subQueries(), optimizedQuery, resolvedEntities, response.targetDocuments() != null ? response.targetDocuments() : List.of());
        }

        return checkDeicticAndReturnSingleSource(cleanQuery, activeDocumentNames != null && !activeDocumentNames.isEmpty(), optimizedQuery, resolvedEntities, response.targetDocuments());
    }

    private RetrievalPlan checkDeicticAndReturnSingleSource(String cleanQuery, boolean hasRecentUpload, String optimizedQuery, List<EntityResolution> entities, List<String> targetDocuments) {
        boolean isDeictic = cleanQuery.matches(".*\\b(this|that|the|given|uploaded|attached|above|provided)\\b.*");
        List<EntityResolution> safeEntities = entities != null ? entities : List.of();

        if (isDeictic && hasRecentUpload) {
                return new RetrievalPlan(Intent.DOCUMENT_QA, Scope.SPECIFIC_DOC, RetrievalStrategy.SINGLE_SOURCE, RetrievalExecutionMode.WHOLE_DOCUMENT, List.of(), List.of(), optimizedQuery, safeEntities, targetDocuments);
        }

        return new RetrievalPlan(Intent.DOCUMENT_QA, Scope.CORPUS, RetrievalStrategy.SINGLE_SOURCE, RetrievalExecutionMode.WHOLE_DOCUMENT, List.of(), List.of(), optimizedQuery, safeEntities, targetDocuments);
    }
}
