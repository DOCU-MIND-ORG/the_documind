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
    private final com.accenture.intern.docmind.repository.DocumentChunkRepository documentChunkRepository;

    private static final Set<String> DEICTIC_WORDS = Set.of(
            "this", "that", "it", "given", "uploaded", "attached", "above", "provided");

    public PlannerService(ChatClient.Builder chatClientBuilder,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper, FastIntentService fastIntentService,
            com.accenture.intern.docmind.repository.DocumentChunkRepository documentChunkRepository) {
        this.chatClient = chatClientBuilder.build();
        this.objectMapper = objectMapper;
        this.fastIntentService = fastIntentService;
        this.documentChunkRepository = documentChunkRepository;
    }

    public Mono<ExecutionPlan> routeQuery(String query, String history, SessionContext sessionContext,
            reactor.core.publisher.Sinks.Many<org.springframework.http.codec.ServerSentEvent<String>> progressSink) {
        String cleanQuery = query.toLowerCase().trim();

        Intent fastIntent = fastIntentService.classifyIntent(cleanQuery);
        if (fastIntent != null) {
            log.info("ROUTER: Fast-pass triggered: {}", fastIntent);
            return Mono
                    .just(new DirectExecutionPlan(
                            "FAST_PASS",
                            new RetrievalPlan(fastIntent.name(), cleanQuery, Collections.emptyList(),
                                    RetrievalExecutionMode.WHOLE_DOCUMENT, Scope.NONE, false),
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
                                    sessionContext, response.entities());
                        }
                        return Mono.just(new AdaptiveExecutionPlan(response.strategy(), "Adaptive retrieval for: " + query, expectedEntities,
                                1, 2, initialPlan, response.entities(), Boolean.TRUE.equals(response.visualSearch())));
                    }

                    List<RetrievalPlan> retrievalPlans = new java.util.ArrayList<>();
                    if (response.plans() != null) {
                        for (LlmRoutingResponse.Plan planDto : response.plans()) {
                            retrievalPlans.add(buildRetrievalPlan(planDto, response, cleanQuery, sessionContext, response.entities()));
                        }
                    }

                    if (retrievalPlans.isEmpty()) {
                        retrievalPlans.add(new RetrievalPlan("Fallback Plan", query, Collections.emptyList(),
                                RetrievalExecutionMode.RANKED_RETRIEVAL, Scope.CORPUS, false));
                    }

                    if ("DECOMPOSE".equals(tier) || retrievalPlans.size() > 1) {
                        log.info("ROUTER: DECOMPOSE execution triggered for query: {}. Generated {} sub-plans.", query, retrievalPlans.size());
                        MergeOperation mergeOp = MergeOperation.NONE;
                        if (response.mergeOperation() != null) {
                            try {
                                mergeOp = MergeOperation.valueOf(response.mergeOperation().toUpperCase());
                            } catch (Exception e) {
                                mergeOp = MergeOperation.NONE;
                            }
                        }
                        return Mono.just(new StaticExecutionPlan(response.strategy(), retrievalPlans, mergeOp, response.entities(),
                                Boolean.TRUE.equals(response.visualSearch())));
                    }

                    log.info("ROUTER: DIRECT execution triggered for query: {}", query);
                    return Mono.just(new DirectExecutionPlan(response.strategy(), retrievalPlans.get(0), response.entities(),
                            Boolean.TRUE.equals(response.visualSearch())));
                });
    }

    private RetrievalPlan buildRetrievalPlan(LlmRoutingResponse.Plan planDto, LlmRoutingResponse response,
            String cleanQuery, SessionContext sessionContext, List<EntityResolution> entities) {
        String optimizedQuery = planDto.optimizedQuery() != null && !planDto.optimizedQuery().isBlank()
                ? planDto.optimizedQuery()
                : cleanQuery;
        List<String> targetDocuments = planDto.targetDocuments() != null ? new java.util.ArrayList<>(planDto.targetDocuments())
                : new java.util.ArrayList<>();

        RetrievalExecutionMode executionMode;
        String rawMode = response.retrievalMode() != null ? response.retrievalMode().toUpperCase() : "RANKED";
        if ("WHOLE_DOCUMENT".equals(rawMode)) {
            executionMode = RetrievalExecutionMode.WHOLE_DOCUMENT;
        } else if ("CONTIGUOUS".equals(rawMode)) {
            executionMode = RetrievalExecutionMode.CONTIGUOUS;
        } else {
            executionMode = RetrievalExecutionMode.RANKED_RETRIEVAL;
        }

        if (executionMode == RetrievalExecutionMode.WHOLE_DOCUMENT || executionMode == RetrievalExecutionMode.CONTIGUOUS) {
            String matchedDoc = null;

            // 1. Check Session Documents
            if (sessionContext != null && sessionContext.uploadedDocuments() != null && !sessionContext.uploadedDocuments().isEmpty()) {
                int docCount = sessionContext.uploadedDocuments().size();
                if (docCount == 1) {
                    matchedDoc = sessionContext.uploadedDocuments().get(0).filename();
                } else {
                    if (!targetDocuments.isEmpty()) {
                        String llmTarget = targetDocuments.get(0).toLowerCase();
                        for (DocumentReference doc : sessionContext.uploadedDocuments()) {
                            if (doc.filename().toLowerCase().contains(llmTarget) || llmTarget.contains(doc.filename().toLowerCase())) {
                                matchedDoc = doc.filename();
                                break;
                            }
                        }
                    }
                    if (matchedDoc == null && entities != null && !entities.isEmpty()) {
                        for (EntityResolution entity : entities) {
                            String entityName = entity.canonicalEntity().toLowerCase();
                            for (DocumentReference doc : sessionContext.uploadedDocuments()) {
                                if (doc.filename().toLowerCase().contains(entityName)) {
                                    matchedDoc = doc.filename();
                                    break;
                                }
                            }
                            if (matchedDoc != null) break;
                        }
                    }
                }
            }

            // 2. Fallback to Global Corpus Documents if not found in session
            if (matchedDoc == null) {
                try {
                    List<String> corpusFiles = documentChunkRepository.findDistinctSourceNames();
                    if (!targetDocuments.isEmpty()) {
                        String llmTarget = targetDocuments.get(0).toLowerCase();
                        for (String f : corpusFiles) {
                            if (f.toLowerCase().contains(llmTarget) || llmTarget.contains(f.toLowerCase())) {
                                matchedDoc = f;
                                break;
                            }
                        }
                    }
                    if (matchedDoc == null && entities != null && !entities.isEmpty()) {
                        for (EntityResolution entity : entities) {
                            String entityName = entity.canonicalEntity().toLowerCase();
                            for (String f : corpusFiles) {
                                if (f.toLowerCase().contains(entityName)) {
                                    matchedDoc = f;
                                    break;
                                }
                            }
                            if (matchedDoc != null) break;
                        }
                    }
                } catch (Exception e) {
                    log.error("Failed to query global corpus for entity resolution: {}", e.getMessage());
                }
            }

            // 3. Finalize
            if (matchedDoc != null) {
                targetDocuments.clear();
                targetDocuments.add(matchedDoc);
            } else {
                executionMode = RetrievalExecutionMode.RANKED_RETRIEVAL;
            }
        }

        boolean isDeictic = cleanQuery.matches(".*\\b(this|that|given|uploaded|attached|above|provided)\\b.*");
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
                targetDocuments, executionMode, scope, Boolean.TRUE.equals(planDto.isStructuralQuery()));
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
                   Triggered when the query is about a SINGLE abstract/implicit topic where the concept
                   won't appear verbatim in the text (e.g. "resilience", "grief", "innovation").
                   NOT for exhaustive enumeration queries ("every", "all", "complete list") — those need
                   ADAPTIVE to verify completeness.
                   → Set "execution_tier": "DECOMPOSE" and "merge_operation": "UNION".
                   → Generate 3-4 separate plans expanding the concept into concrete action-oriented searches.

                2. META_DOC_SEARCH
                   Triggered when the user asks for a semantic corpus search ABOUT their document collection
                   (e.g. "Which uploaded documents discuss black holes?", "Name files that talk about gravity?").
                   → Set "execution_tier": "DIRECT" and generate exactly 1 plan.

                3. SINGLE_SOURCE
                   Default. Standard factual lookup targeting a specific topic within the ACTIVE DOCUMENTS.
                   → Depending on the complexity, this might be "DIRECT" (1 plan) or "DECOMPOSE" (multiple plans).

                4. TIMELINE_QUERY
                   Triggered when the user asks chronological questions about the conversation history itself.
                   (e.g. "What did we discuss today?", "What topics did we cover?", "Did we talk about X earlier?")
                   → Set "execution_tier": "DIRECT" and generate exactly 1 plan with "purpose": "TIMELINE_QUERY".

                5. EPISODIC_SUMMARY
                   Triggered when the user asks for a broad summary of past conversations on a specific topic.
                   (e.g. "Remind me about our past discussion on SpaceX.", "What were the key takeaways from our chat about black holes?")
                   → Set "execution_tier": "DIRECT" and generate exactly 1 plan with "purpose": "EPISODIC_SUMMARY".

                6. SPECIFIC_TOPIC
                   Triggered when the user asks a very specific factual lookup from PAST CONVERSATIONS (not uploaded documents).
                   (e.g. "What did you say the distance to the moon was?", "What was that python library you mentioned?")
                   → Set "execution_tier": "DIRECT" and generate exactly 1 plan with "purpose": "SPECIFIC_TOPIC".

                ═══════════════════════════════════════════════════════════════════
                PART B — COMPLEX QUERY DECOMPOSITION & EXECUTION TIER
                ═══════════════════════════════════════════════════════════════════
                Set "execution_tier" to one of:
                - "DIRECT": The query can be resolved in a single step (e.g., "What is X?"). You must output EXACTLY 1 plan.
                - "DECOMPOSE": The full set of sub-queries can be written before any retrieval begins, and the sub-queries are independent (results of one don't affect another). Output MULTIPLE plans.
                - "ADAPTIVE": Use when the next retrieval step depends on what earlier steps discover, OR when the query implies complete coverage ("all", "every", "throughout the book", "across all chapters") and you can't enumerate the relevant sections upfront. Output EXACTLY 1 plan for the FIRST hop.

                Set "merge_operation":
                - "NONE": For DIRECT or ADAPTIVE.
                - "COMPARE": When DECOMPOSE is used to compare entities side-by-side.
                - "UNION": When DECOMPOSE is used to expand a concept or search for multiple aspects of a single entity.

                ═══════════════════════════════════════════════════════════════════
                PART C — RETRIEVAL PLANS
                ═══════════════════════════════════════════════════════════════════
                You must generate a list of "plans". Each plan requires:
                - "purpose": A short description of what this plan is looking for.
                - "optimized_query": A keyword-dense rewrite of the user's query. Expand abstract concepts, BUT you MUST preserve the user's core exact nouns (e.g. "laws", "list", "chapters", "index"). Do not aggressively replace them with abstract synonyms (e.g. replacing "laws" with "principles"). Preserve, then expand.
                - "target_documents": An array of specific filenames if requested.
                - "is_structural_query": Set to true IF the user is explicitly asking for the document's structure, table of contents, chapters, list of topics, or an overview outline of the document. Otherwise false.

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
                - Default. Use for facts, explanations, comparisons, definitions, QA over isolated passages.
                - Examples: "What is narcissism?", "Who was Pericles?", "What are the 18 laws?"

                CONTIGUOUS
                - Use when the answer requires reading one logical section in its original sequential order.
                  Choose CONTIGUOUS whenever preserving the flow of one section matters more than selecting
                  isolated high-relevance passages. The retriever will find the section automatically —
                  you do NOT need to name the chapter.
                - Examples: "Tell me the story of Pericles", "Walk me through the case study of Steve Jobs",
                  "Explain Law 1 in detail", "What happens in the opening example?", "Describe the narrative"

                WHOLE_DOCUMENT
                - Use ONLY for true whole-document operations that require reading the entire document:
                  summarizing the whole book, comparing all chapters, generating a full overview.
                - Do NOT use for single-section narrative queries — use CONTIGUOUS instead.
                - Examples: "Summarize the entire Laws of Human Nature", "Compare all 18 laws"

                ═══════════════════════════════════════════════════════════════════
                PART F — VISUAL SEARCH
                ═══════════════════════════════════════════════════════════════════        
                Set "visual_search": true if the user explicitly asks for an image, picture, visual, diagram, photo, wallpaper, screenshot, or graphic (e.g. "show me images", "are there pictures"), OR if the query involves:
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
                  "retrieval_mode": "RANKED | CONTIGUOUS | WHOLE_DOCUMENT",
                  "merge_operation": "NONE | UNION | COMPARE",
                  "visual_search": false,
                  "entities": [ { "name": "resolved canonical entity name", "confidence": 0.0 } ],
                  "plans": [
                     {
                        "purpose": "What this plan searches for",
                        "optimized_query": "keyword-dense query",
                        "target_documents": ["resolved exact filenames"],
                        "is_structural_query": false
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
                                    java.util.Collections.emptyList(), false)),
                            false));
                });
    }
}
