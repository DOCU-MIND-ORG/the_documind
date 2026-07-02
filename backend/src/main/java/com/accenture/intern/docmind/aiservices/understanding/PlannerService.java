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

    private static final Set<String> DEICTIC_WORDS = Set.of(
            "this", "that", "it", "given", "uploaded", "attached", "above", "provided");

    public PlannerService(ChatClient.Builder chatClientBuilder,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper, FastIntentService fastIntentService) {
        this.chatClient = chatClientBuilder.build();
        this.objectMapper = objectMapper;
        this.fastIntentService = fastIntentService;
    }

    public Mono<ExecutionPlan> routeQuery(String query, String history, SessionContext sessionContext,
            reactor.core.publisher.Sinks.Many<org.springframework.http.codec.ServerSentEvent<String>> progressSink) {
        String cleanQuery = query.toLowerCase().trim();

        Intent fastIntent = fastIntentService.classifyIntent(cleanQuery);
        if (fastIntent != null) {
            log.info("ROUTER: Fast-pass triggered: {}", fastIntent);
            return Mono
                    .just(new DirectExecutionPlan(
                            new RetrievalPlan(fastIntent.name(), cleanQuery, Collections.emptyList(),
                                    RetrievalExecutionMode.WHOLE_DOCUMENT, Scope.NONE),
                            Collections.emptyList(), false));
        }

        return runUnifiedLlmRouter(cleanQuery, query, history, sessionContext, progressSink)
                .flatMap(response -> {
                    String tier = response.executionTier() != null ? response.executionTier().toUpperCase() : "DIRECT";

                    if ("ADAPTIVE".equals(tier)) {
                        log.info("ROUTER: Adaptive execution triggered for query: {}", query);
                        List<String> expectedEntities = response.entities() != null ? response.entities().stream().map(
                                com.accenture.intern.docmind.aiservices.understanding.EntityResolution::canonicalEntity)
                                .toList() : Collections.emptyList();

                        RetrievalPlan initialPlan = null;
                        if (response.plans() != null && !response.plans().isEmpty()) {
                            initialPlan = buildRetrievalPlan(response.plans().get(0), response, cleanQuery,
                                    sessionContext);
                        }
                        return Mono.just(new AdaptiveExecutionPlan("Adaptive retrieval for: " + query, expectedEntities,
                                1, 2, initialPlan, response.entities(), Boolean.TRUE.equals(response.visualSearch())));
                    }

                    List<RetrievalPlan> retrievalPlans = new java.util.ArrayList<>();
                    if (response.plans() != null) {
                        for (LlmRoutingResponse.Plan planDto : response.plans()) {
                            retrievalPlans.add(buildRetrievalPlan(planDto, response, cleanQuery, sessionContext));
                        }
                    }

                    if (retrievalPlans.isEmpty()) {
                        retrievalPlans.add(new RetrievalPlan("Fallback Plan", query, Collections.emptyList(),
                                RetrievalExecutionMode.RANKED_RETRIEVAL, Scope.CORPUS));
                    }

                    if ("DECOMPOSE".equals(tier) || retrievalPlans.size() > 1) {
                        MergeOperation mergeOp = MergeOperation.NONE;
                        if (response.mergeOperation() != null) {
                            try {
                                mergeOp = MergeOperation.valueOf(response.mergeOperation().toUpperCase());
                            } catch (Exception e) {
                                mergeOp = MergeOperation.NONE;
                            }
                        }
                        return Mono.just(new StaticExecutionPlan(retrievalPlans, mergeOp, response.entities(),
                                Boolean.TRUE.equals(response.visualSearch())));
                    }

                    return Mono.just(new DirectExecutionPlan(retrievalPlans.get(0), response.entities(),
                            Boolean.TRUE.equals(response.visualSearch())));
                });
    }

    private RetrievalPlan buildRetrievalPlan(LlmRoutingResponse.Plan planDto, LlmRoutingResponse response,
            String cleanQuery, SessionContext sessionContext) {
        String optimizedQuery = planDto.optimizedQuery() != null && !planDto.optimizedQuery().isBlank()
                ? planDto.optimizedQuery()
                : cleanQuery;
        List<String> targetDocuments = planDto.targetDocuments() != null ? planDto.targetDocuments()
                : Collections.emptyList();

        RetrievalExecutionMode executionMode = "WHOLE_DOCUMENT".equalsIgnoreCase(response.retrievalMode())
                ? RetrievalExecutionMode.WHOLE_DOCUMENT
                : RetrievalExecutionMode.RANKED_RETRIEVAL;

        boolean isDeictic = cleanQuery.matches(".*\\b(this|that|the|given|uploaded|attached|above|provided)\\b.*");
        boolean hasRecentUpload = sessionContext != null && sessionContext.uploadedDocuments() != null
                && !sessionContext.uploadedDocuments().isEmpty();
        Scope scope = (isDeictic && hasRecentUpload) ? Scope.SPECIFIC_DOC : Scope.CORPUS;

        String purpose = planDto.purpose() != null ? planDto.purpose() : "Retrieval";
        if (Boolean.TRUE.equals(response.isBotQa())) {
            scope = Scope.NONE;
            executionMode = RetrievalExecutionMode.WHOLE_DOCUMENT;
            purpose = "BOT_QA";
        }

        return new RetrievalPlan(purpose, optimizedQuery,
                targetDocuments, executionMode, scope);
    }

    private Mono<LlmRoutingResponse> runUnifiedLlmRouter(String cleanQuery, String originalQuery,
            String history, SessionContext sessionContext,
            reactor.core.publisher.Sinks.Many<org.springframework.http.codec.ServerSentEvent<String>> progressSink) {

        return callUnifiedRouter(originalQuery, history, sessionContext)
                .doOnNext(response -> {
                    if (progressSink != null) {
                        String tier = response.executionTier() != null ? response.executionTier() : "DIRECT";
                        java.util.Map<String, Object> meta = new java.util.HashMap<>();
                        meta.put("tier", tier);
                        if (response.plans() != null && !response.plans().isEmpty()) {
                            meta.put("optimized_query", response.plans().get(0).optimizedQuery());
                        }

                        String msg = new com.accenture.intern.docmind.dto.chat.ProgressEvent(
                                com.accenture.intern.docmind.dto.chat.ProgressStage.PLANNING,
                                com.accenture.intern.docmind.dto.chat.ProgressStatus.COMPLETE,
                                "Planning retrieval...", null, null, meta).toJson(objectMapper);
                        progressSink.tryEmitNext(org.springframework.http.codec.ServerSentEvent.<String>builder(msg)
                                .event("progress").build());
                    }
                });
    }

    private Mono<LlmRoutingResponse> callUnifiedRouter(String query, String history, SessionContext sessionContext) {
        List<String> uploadedDocs = sessionContext != null && sessionContext.uploadedDocuments() != null
                ? sessionContext.uploadedDocuments().stream().map(DocumentReference::filename).toList()
                : Collections.emptyList();
        java.util.Map<String, String> aliases = sessionContext != null ? sessionContext.aliases()
                : Collections.emptyMap();

        String activeDocsStr = uploadedDocs != null && !uploadedDocs.isEmpty()
                ? String.join("\n- ", uploadedDocs)
                : "None";

        StringBuilder aliasesStr = new StringBuilder();
        if (aliases != null && !aliases.isEmpty()) {
            aliases.forEach((alias, filename) -> aliasesStr.append("- ").append(alias).append(" -> ").append(filename)
                    .append("\n"));
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
                    ═══════════════════════════════════════════════════════════════════
                PART A — STRATEGY CLASSIFICATION
                ═══════════════════════════════════════════════════════════════════

                Classify into EXACTLY ONE of these strategies:

                1. CONCEPT_EXPANSION
                   Triggered when the query is about a SINGLE abstract/implicit topic with no obvious
                   keyword match in typical document text (e.g. "resilience", "grief", "innovation").
                   → Set "execution_tier": "DECOMPOSE" and "merge_operation": "UNION".
                   → Generate 3-4 separate plans expanding the concept into concrete action-oriented searches.

                2. META_DOC_SEARCH
                   Triggered when the user asks for a semantic corpus search ABOUT their document collection
                   (e.g. "Which uploaded documents discuss black holes?", "Name files that talk about gravity?").
                   → Set "execution_tier": "DIRECT" and generate exactly 1 plan.

                3. SINGLE_SOURCE
                   Default. Standard factual lookup targeting a specific topic.
                   → Depending on the complexity, this might be "DIRECT" (1 plan) or "DECOMPOSE" (multiple plans).

                ═══════════════════════════════════════════════════════════════════
                PART B — COMPLEX QUERY DECOMPOSITION & EXECUTION TIER
                ═══════════════════════════════════════════════════════════════════
                Set "execution_tier" to one of:
                - "DIRECT": The query can be resolved in a single step (e.g., "What is X?"). You must output EXACTLY 1 plan.
                - "DECOMPOSE": The query asks to compare or retrieve multiple independent entities/topics (e.g. "Compare X and Y"). You must output MULTIPLE plans (one for each entity).
                - "ADAPTIVE": The query explicitly requires a goal-oriented, multi-hop search loop. You must output EXACTLY 1 plan for the FIRST hop.

                Set "merge_operation":
                - "NONE": For DIRECT or ADAPTIVE.
                - "COMPARE": When DECOMPOSE is used to compare entities side-by-side.
                - "UNION": When DECOMPOSE is used to expand a concept or search for multiple aspects of a single entity.

                ═══════════════════════════════════════════════════════════════════
                PART C — RETRIEVAL PLANS
                ═══════════════════════════════════════════════════════════════════
                You must generate a list of "plans". Each plan requires:
                - "purpose": A short description of what this plan is looking for.
                - "optimized_query": A keyword-dense rewrite of the user's query. Expand abstract concepts into concrete keywords. Resolve references (like "this", "that") using conversation history.
                - "target_documents": An array of specific filenames if requested.

                ═══════════════════════════════════════════════════════════════════
                PART D — ENTITY EXTRACTION WITH CONFIDENCE
                ═══════════════════════════════════════════════════════════════════
                For each entity you identify, also estimate your confidence (0.0–1.0) that it is the
                correct resolution of the user's reference. Be conservative.

                ═══════════════════════════════════════════════════════════════════
                PART E — RETRIEVAL MODE SELECTION
                ═══════════════════════════════════════════════════════════════════
                Choose exactly one retrieval mode:

                RANKED
                - Default. Use for facts, explanations, comparisons, QA.

                WHOLE_DOCUMENT
                - Use only when the user explicitly wants the document as a whole (summarize this document, review this report).

                ═══════════════════════════════════════════════════════════════════
                PART F — VISUAL SEARCH
                ═══════════════════════════════════════════════════════════════════        
                Set "visual_search": true if the user explicitly asks for an image, OR if the query involves:
                - Architectures, system designs, or workflows
                - Geographical or spatial relationships
                - Data trends, comparisons, or financial metrics
                - UI layouts or physical descriptions
                In these cases, a diagram, chart, or photo would significantly enhance the answer, even if the user didn't type the word "image".


                ═══════════════════════════════════════════════════════════════════
                OUTPUT — Return ONLY a valid JSON object, no explanation:
                ═══════════════════════════════════════════════════════════════════
                {
                  "is_bot_qa": false,
                  "strategy": "SINGLE_SOURCE | CONCEPT_EXPANSION | META_DOC_SEARCH",
                  "execution_tier": "DIRECT | DECOMPOSE | ADAPTIVE",
                  "retrieval_mode": "RANKED | WHOLE_DOCUMENT",
                  "merge_operation": "NONE | UNION | COMPARE",
                  "visual_search": false,
                  "entities": [ { "name": "resolved canonical entity name", "confidence": 0.0 } ],
                  "plans": [
                     {
                        "purpose": "What this plan searches for",
                        "optimized_query": "keyword-dense query",
                        "target_documents": ["resolved exact filenames"]
                     }
                  ]
                }
                ═══════════════════════════════════════════════════════════════════
                CONVERSATION HISTORY
                ═══════════════════════════════════════════════════════════════════
                %s

                User Query: "%s"
                """
                .formatted(activeDocsStr, aliasesStr.toString(), history != null ? history : "No prior history.",
                        query);

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
                .doOnNext(res -> {
                    System.out.println("""
                            === Query Understanding ===
                            Visual Search : %s
                            Retrieval Mode: %s
                            Execution Tier: %s
                            Plans: %d
                            ==========================
                            """.formatted(
                            res.visualSearch(),
                            res.retrievalMode(),
                            res.executionTier(),
                            res.plans() != null ? res.plans().size() : 0));
                    log.info("[TIMING] Unified LLM Router: {}ms | strategy={} | execution_tier={}",
                            System.currentTimeMillis() - t0, res.strategy(), res.executionTier());
                })
                .retry(1)
                .onErrorResume(e -> {
                    String fallbackStrategy = "SINGLE_SOURCE";
                    log.error("Unified LLM Router failed. Falling back to {} using heuristics. Error: {}",
                            fallbackStrategy, e.getMessage());
                    return Mono.just(new LlmRoutingResponse(
                            fallbackStrategy,
                            false,
                            java.util.Collections.emptyList(),
                            "DIRECT",
                            "RANKED",
                            "NONE",
                            java.util.List.of(new LlmRoutingResponse.Plan("Fallback Plan", query,
                                    java.util.Collections.emptyList())),
                            false));
                });
    }
}
