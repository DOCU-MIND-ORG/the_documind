package com.accenture.intern.docmind.aiservices.context;

import com.accenture.intern.docmind.config.RetrievalProperties;
import com.accenture.intern.docmind.dto.chat.ContextResult;
import com.accenture.intern.docmind.dto.chat.RetrievalTrace;
import com.accenture.intern.docmind.entity.Message;
import com.accenture.intern.docmind.entity.MessageRole;
import com.accenture.intern.docmind.repository.MessageRepository;
import com.accenture.intern.docmind.dto.context.SessionContext;
import com.accenture.intern.docmind.dto.context.DocumentReference;
import com.accenture.intern.docmind.aiservices.understanding.PlannerService;
import com.accenture.intern.docmind.aiservices.understanding.plan.ExecutionPlan;
import com.accenture.intern.docmind.aiservices.understanding.plan.DirectExecutionPlan;
import com.accenture.intern.docmind.aiservices.understanding.plan.StaticExecutionPlan;
import com.accenture.intern.docmind.aiservices.understanding.plan.AdaptiveExecutionPlan;
import com.accenture.intern.docmind.aiservices.understanding.Intent;
import com.accenture.intern.docmind.aiservices.understanding.RetrievalStrategy;
import com.accenture.intern.docmind.aiservices.understanding.Scope;
import com.accenture.intern.docmind.aiservices.understanding.RetrievalPlan;

import com.accenture.intern.docmind.aiservices.understanding.MergeOperation;
import com.accenture.intern.docmind.aiservices.retrieval.HybridRetrievalService;
import com.accenture.intern.docmind.aiservices.retrieval.RetrievalOrchestrator;
import com.accenture.intern.docmind.dto.retrieval.RetrievalCandidate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ContextBuilderService {

    private static final int MAX_HISTORY_MESSAGES = 10;

    private final HybridRetrievalService hybridRetrievalService;
    private final MessageRepository messageRepository;
    private final PlannerService plannerService;
    private final RetrievalOrchestrator retrievalOrchestrator;
    private final com.accenture.intern.docmind.aiservices.understanding.plan.RetrievalController retrievalController;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final String ragPrompt;
    private final String generalPrompt;

    public ContextBuilderService(
            HybridRetrievalService hybridRetrievalService,
            MessageRepository messageRepository,
            PlannerService plannerService,
            RetrievalOrchestrator retrievalOrchestrator,
            com.accenture.intern.docmind.aiservices.understanding.plan.RetrievalController retrievalController,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper,
            @Value("classpath:prompts/ragprompt.st") Resource ragPromptResource,
            @Value("classpath:prompts/generalprompt.st") Resource generalPromptResource) throws IOException {
        this.hybridRetrievalService = hybridRetrievalService;
        this.messageRepository = messageRepository;
        this.plannerService = plannerService;
        this.retrievalOrchestrator = retrievalOrchestrator;
        this.retrievalController = retrievalController;
        this.objectMapper = objectMapper;
        this.ragPrompt = StreamUtils.copyToString(ragPromptResource.getInputStream(), StandardCharsets.UTF_8);
        this.generalPrompt = StreamUtils.copyToString(generalPromptResource.getInputStream(), StandardCharsets.UTF_8);
    }

    public Mono<ContextResult> buildContext(String question, Long sessionId, SessionContext sessionContext, boolean stillIndexing, reactor.core.publisher.Sinks.Many<org.springframework.http.codec.ServerSentEvent<String>> progressSink) {

        boolean skipSemanticMemory = true; 
        return fetchHistoryBlock(sessionId, question, skipSemanticMemory).flatMap(historyBlock -> {
            return plannerService.routeQuery(question, historyBlock, sessionContext, progressSink).flatMap(execPlan -> {
                
                RetrievalPlan decision = null;
                boolean decompositionRequired = false;
                MergeOperation mergeOperation = MergeOperation.NONE;
                List<RetrievalPlan> plans = Collections.emptyList();

                if (execPlan instanceof DirectExecutionPlan dir) {
                    decision = dir.retrievalPlan();
                    plans = List.of(decision);
                } else if (execPlan instanceof StaticExecutionPlan stat) {
                    decision = stat.plans().isEmpty() ? null : stat.plans().get(0);
                    decompositionRequired = true;
                    mergeOperation = stat.mergeOperation();
                    plans = stat.plans();
                } else if (execPlan instanceof AdaptiveExecutionPlan adapt) {
                    return orchestrateAdaptive(question, sessionId, adapt, Mono.just(historyBlock), sessionContext, progressSink);
                }

                if (decision != null && (decision.intent() == Intent.GREETING_ACK || decision.intent() == Intent.BOT_QA)) {
                    return Mono.just(new ContextResult(generalPrompt, buildPrompt(question, "", historyBlock, sessionContext), Collections.emptyList(), 0.0));
                }

                if (decision != null && ((decision.intent() == Intent.META_HISTORY || decision.intent() == Intent.CLARIFICATION) && decision.scope() == Scope.NONE)) {
                    String metaContext = "Context: The user is asking about past conversations or asking for clarification. Rely on the Conversation History above.";
                    return Mono.just(new ContextResult(ragPrompt, buildPrompt(question, metaContext, historyBlock, sessionContext), Collections.emptyList(), 0.0));
                }

                if (decision != null && decision.intent() == Intent.SESSION_INFO) {
                    List<String> activeDocs = sessionContext != null && sessionContext.uploadedDocuments() != null ? sessionContext.uploadedDocuments().stream().map(DocumentReference::filename).toList() : java.util.Collections.emptyList();
                    String activeDocsStr = activeDocs != null && !activeDocs.isEmpty()
                            ? String.join("\n- ", activeDocs)
                            : "No active documents in this session.";
                    String syntheticContext = "SESSION_INFO:\nActive session documents uploaded by the user:\n" + activeDocsStr;
                    return Mono.just(new ContextResult(ragPrompt, buildPrompt(question, syntheticContext, historyBlock, sessionContext), java.util.Collections.emptyList(), 1.0));
                }

                if (!decompositionRequired && decision != null && (decision.scope() == Scope.SPECIFIC_DOC || stillIndexing)) {
                    List<String> targetDocs = decision.targetDocuments();
                    if (targetDocs == null || targetDocs.isEmpty()) {
                        if (sessionContext != null && sessionContext.activeDocument() != null) {
                            targetDocs = List.of(sessionContext.activeDocument());
                        } else if (sessionContext != null && sessionContext.uploadedDocuments() != null && !sessionContext.uploadedDocuments().isEmpty()) {
                            targetDocs = List.of(sessionContext.uploadedDocuments().get(0).filename());
                        }
                    }
                    if (targetDocs != null && !targetDocs.isEmpty()) {
                        MergeOperation finalMergeOperation = mergeOperation;
                        RetrievalPlan finalDecision = decision;
                        return reactor.core.publisher.Flux.fromIterable(targetDocs)
                                .flatMap(docName -> hybridRetrievalService.wholeDocumentRetrieve(docName))
                                .reduce(new ArrayList<RetrievalCandidate>(), (acc, list) -> {
                                    acc.addAll(list);
                                    return acc;
                                })
                                .map(selectedDocs -> new ContextResult(
                                        getSystemPrompt(selectedDocs.isEmpty(), finalDecision.retrievalStrategy(), historyBlock, sessionContext),
                                        buildPrompt(question, buildContextBlock(selectedDocs, finalMergeOperation), historyBlock, sessionContext),
                                        selectedDocs, 1.0
                                ));
                    }
                }

                StaticExecutionPlan staticPlan = new StaticExecutionPlan(plans, mergeOperation);
                return orchestrateAndBuild(question, sessionId, staticPlan, Mono.just(historyBlock), sessionContext, progressSink);
            });
        });
    }

    private Mono<ContextResult> orchestrateAdaptive(String question, Long sessionId, AdaptiveExecutionPlan adaptivePlan, Mono<String> historyBlockMono, SessionContext sessionContext, reactor.core.publisher.Sinks.Many<org.springframework.http.codec.ServerSentEvent<String>> progressSink) {
        RetrievalPlan initialPlan = new RetrievalPlan(
                Intent.DOCUMENT_QA,
                Scope.CORPUS,
                RetrievalStrategy.SINGLE_SOURCE,
                com.accenture.intern.docmind.aiservices.understanding.RetrievalExecutionMode.RANKED_RETRIEVAL,
                List.of(),
                List.of(),
                question,
                List.of(),
                List.of()
        );
        RetrievalTrace trace = new RetrievalTrace();
        return adaptiveLoop(adaptivePlan, initialPlan, new ArrayList<>(), 1, sessionId, question, historyBlockMono, sessionContext, progressSink, trace);
    }

    /**
     * Forces the adaptive multi-iteration retrieval loop directly, bypassing
     * {@link PlannerService#routeQuery} entirely. Used by
     * {@link com.accenture.intern.docmind.aiservices.chat.LlmWorkerService} as
     * a fallback retry: if a normal (single-pass) answer comes back as the
     * "couldn't find relevant information" sentinel, we don't just give up —
     * we re-attempt retrieval using the same adaptive search-and-refine method
     * the planner reserves for hard queries, in case the simpler retrieval
     * strategy just didn't dig deep enough the first time.
     */
    public Mono<ContextResult> buildContextAdaptive(String question, Long sessionId, SessionContext sessionContext) {
        AdaptiveExecutionPlan adaptivePlan = new AdaptiveExecutionPlan(
                "Adaptive retry retrieval for: " + question,
                Collections.emptyList(),
                1,
                3
        );
        Mono<String> historyBlockMono = fetchHistoryBlock(sessionId, question, true);
        return orchestrateAdaptive(question, sessionId, adaptivePlan, historyBlockMono, sessionContext, null);
    }

    private Mono<ContextResult> adaptiveLoop(AdaptiveExecutionPlan adaptivePlan, RetrievalPlan currentPlan, List<RetrievalCandidate> accumulatedCandidates, int iteration, Long sessionId, String originalQuestion, Mono<String> historyBlockMono, SessionContext sessionContext, reactor.core.publisher.Sinks.Many<org.springframework.http.codec.ServerSentEvent<String>> progressSink, RetrievalTrace trace) {
        if (iteration > adaptivePlan.maxIterations()) {
            return buildFinalAdaptiveResult(originalQuestion, accumulatedCandidates, historyBlockMono, sessionContext, trace);
        }
        
        StaticExecutionPlan singleStatic = new StaticExecutionPlan(List.of(currentPlan), MergeOperation.UNION);
        return retrievalOrchestrator.orchestrate(originalQuestion, sessionId, singleStatic, progressSink)
            .flatMap(newCandidates -> {
                if (progressSink != null) {
                    String msg = new com.accenture.intern.docmind.dto.chat.ProgressEvent(
                            com.accenture.intern.docmind.dto.chat.ProgressStage.ADAPTIVE,
                            com.accenture.intern.docmind.dto.chat.ProgressStatus.INFO,
                            "Analyzing retrieved evidence...", iteration, adaptivePlan.maxIterations(), null
                    ).toJson(objectMapper);
                    progressSink.tryEmitNext(org.springframework.http.codec.ServerSentEvent.<String>builder(msg).event("progress").build());
                }

                com.accenture.intern.docmind.aiservices.understanding.plan.RetrievalObservation obs = retrievalOrchestrator.generateObservation(newCandidates, currentPlan);
                trace.addObservation(obs);
                trace.addStep(String.format("Iteration %d: %s", iteration, obs.message()));
                accumulatedCandidates.addAll(newCandidates);
                
                return retrievalController.decideNextAction(adaptivePlan, obs)
                    .flatMap(action -> {
                        trace.addStep(String.format("Action chosen: %s. Reason: %s", action.type().name(), action.reasoning()));
                        if (action.type() == com.accenture.intern.docmind.aiservices.understanding.plan.AdaptiveAction.AdaptiveActionType.STOP || action.nextPlan().isEmpty()) {
                            return buildFinalAdaptiveResult(originalQuestion, accumulatedCandidates, historyBlockMono, sessionContext, trace);
                        } else {
                            if (progressSink != null) {
                                String targetStr = action.nextPlan().get().entities().stream().findFirst()
                                    .map(e -> e.canonicalEntity())
                                    .orElse(action.nextPlan().get().optimizedQuery());
                                String msg = new com.accenture.intern.docmind.dto.chat.ProgressEvent(
                                        com.accenture.intern.docmind.dto.chat.ProgressStage.RETRIEVAL,
                                        com.accenture.intern.docmind.dto.chat.ProgressStatus.RUNNING,
                                        "Searching for additional evidence about " + targetStr + "...", iteration, adaptivePlan.maxIterations(), null
                                ).toJson(objectMapper);
                                progressSink.tryEmitNext(org.springframework.http.codec.ServerSentEvent.<String>builder(msg).event("progress").build());
                            }
                            return adaptiveLoop(adaptivePlan, action.nextPlan().get(), accumulatedCandidates, iteration + 1, sessionId, originalQuestion, historyBlockMono, sessionContext, progressSink, trace);
                        }
                    });
            });
    }

    private Mono<ContextResult> buildFinalAdaptiveResult(String question, List<RetrievalCandidate> candidates, Mono<String> historyBlockMono, SessionContext sessionContext, RetrievalTrace trace) {
        return historyBlockMono.flatMap(historyBlock -> {
             trace.addStep(String.format("Adaptive Loop finished, returning %d chunks.", candidates.size()));
             double topScore = candidates.isEmpty() ? 0.0 : candidates.get(0).finalScore();
             String contextBlock = buildContextBlock(candidates, MergeOperation.UNION);
             String augmentedPrompt = buildPrompt(question, contextBlock, historyBlock, sessionContext);
             return Mono.just(new ContextResult(
                     getSystemPrompt(candidates.isEmpty(), RetrievalStrategy.SINGLE_SOURCE, historyBlock, sessionContext),
                     augmentedPrompt, candidates, candidates, Collections.emptyList(), Collections.emptyList(), trace, topScore));
        });
    }

    private Mono<ContextResult> orchestrateAndBuild(String question, Long sessionId, StaticExecutionPlan execPlan, Mono<String> historyBlockMono, SessionContext sessionContext, reactor.core.publisher.Sinks.Many<org.springframework.http.codec.ServerSentEvent<String>> progressSink) {
        return retrievalOrchestrator.orchestrate(question, sessionId, execPlan, progressSink)
                .zipWith(historyBlockMono)
                .flatMap(tuple -> {
                    List<RetrievalCandidate> candidates = tuple.getT1();
                    String historyBlock = tuple.getT2();

                    RetrievalTrace trace = new RetrievalTrace();
                    trace.addStep(String.format("Orchestrator returned %d merged chunks.", candidates.size()));

                    double topScore = candidates.isEmpty() ? 0.0 : candidates.get(0).finalScore();
                    
                    RetrievalStrategy primaryStrategy = execPlan.plans().isEmpty() ? RetrievalStrategy.SINGLE_SOURCE : execPlan.plans().get(0).retrievalStrategy();

                    String contextBlock = buildContextBlock(candidates, execPlan.mergeOperation());
                    String augmentedPrompt = buildPrompt(question, contextBlock, historyBlock, sessionContext);
                    
                    return Mono.just(new ContextResult(
                            getSystemPrompt(candidates.isEmpty(), primaryStrategy, historyBlock, sessionContext),
                            augmentedPrompt, candidates, candidates, Collections.emptyList(), Collections.emptyList(), trace, topScore));
                });
    }

    private Mono<String> fetchHistoryBlock(Long sessionId, String query, boolean skipSemanticMemory) {
        return Mono.fromCallable(() -> {
                    List<Message> history = messageRepository.findTop10BySession_SessionIdOrderByCreatedAtDesc(sessionId);
                    List<Message> limitedHistory = history.stream()
                            .limit(MAX_HISTORY_MESSAGES)
                            .toList();
                    List<Message> reversedHistory = new ArrayList<>(limitedHistory);
                    Collections.reverse(reversedHistory);

                    StringBuilder historyBuilder = new StringBuilder();
                    for (Message msg : reversedHistory) {
                        String prefix = msg.getRole() == MessageRole.USER ? "User" : "Assistant";
                        historyBuilder.append(prefix).append(": ").append(msg.getContent()).append("\n");
                    }
                    return historyBuilder.toString().trim();
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    log.warn("Failed to load conversation history for session {}: {}", sessionId, e.getMessage());
                    return Mono.just("");
                });
    }

    private String buildContextBlock(List<RetrievalCandidate> docs, MergeOperation mergeOperation) {
        if (docs.isEmpty()) {
            return "No relevant documents found.";
        }

        StringBuilder sb = new StringBuilder();
        
        if (mergeOperation == MergeOperation.COMPARE) {
            java.util.Map<String, List<RetrievalCandidate>> grouped = new java.util.LinkedHashMap<>();
            for (RetrievalCandidate cand : docs) {
                String purpose = (String) cand.chunk().getMetadata().getOrDefault("purpose", "General Retrieval");
                grouped.computeIfAbsent(purpose, k -> new ArrayList<>()).add(cand);
            }
            
            int index = 1;
            for (java.util.Map.Entry<String, List<RetrievalCandidate>> entry : grouped.entrySet()) {
                sb.append("=== Evidence for: ").append(entry.getKey()).append(" ===\n");
                for (RetrievalCandidate cand : entry.getValue()) {
                    String name = (String) cand.chunk().getMetadata().getOrDefault("sourceName", "unknown");
                    String type = (String) cand.chunk().getMetadata().getOrDefault("sourceType", "");
                    sb.append(String.format("<CITATION id=\"%d\">\nSource: %s | Type: %s\n%s\n</CITATION>\n\n",
                            index++, name, type, cand.chunk().getText()));
                }
            }
        } else {
            int index = 1;
            for (RetrievalCandidate cand : docs) {
                String name = (String) cand.chunk().getMetadata().getOrDefault("sourceName", "unknown");
                String type = (String) cand.chunk().getMetadata().getOrDefault("sourceType", "");
                sb.append(String.format("<CITATION id=\"%d\">\nSource: %s | Type: %s\n%s\n</CITATION>\n\n",
                        index++, name, type, cand.chunk().getText()));
            }
        }

        return sb.toString().trim();
    }

    private String buildPrompt(String question, String contextBlock, String historyBlock, SessionContext sessionContext) {
        String sessionContextStr = "";
        if (sessionContext != null && sessionContext.uploadedDocuments() != null && !sessionContext.uploadedDocuments().isEmpty()) {
            List<String> docs = sessionContext.uploadedDocuments().stream().map(DocumentReference::filename).toList();
            sessionContextStr = "Runtime Session Context:\nUploaded Files:\n- " + String.join("\n- ", docs) + "\n";
            if (sessionContext.activeDocument() != null) {
                sessionContextStr += "Currently Active File in UI: " + sessionContext.activeDocument() + "\n";
            }
        }
        
        return "Context Information:\n" + contextBlock + "\n\n" + sessionContextStr + "\nConversation History:\n" + historyBlock + "\n\nUser Question:\n" + question;
    }

    private String getSystemPrompt(boolean isEmpty, RetrievalStrategy strategy, String historyBlock, SessionContext sessionContext) {
        if (isEmpty && (historyBlock == null || historyBlock.isBlank())) return generalPrompt;

        String directive;
        if (strategy == RetrievalStrategy.CONCEPT_EXPANSION) {
            directive = "Never say a concept is absent because the exact word is missing. " +
                    "Infer the concept from the underlying evidence present in the retrieved context. " +
                    "Map thematic evidence to the abstract concept the user asked about and answer accordingly.";
        } else if (strategy == RetrievalStrategy.META_DOC_SEARCH) {
            directive = "Your task is to identify WHICH documents are relevant to the user's query and name them explicitly. " +
                    "CRITICAL RULE: Do NOT require the exact query word or phrase to appear verbatim in the document. " +
                    "Instead, identify the underlying theme or concept in the user's question, then evaluate each document's content " +
                    "to determine if it thematically relates to that concept — even if different vocabulary is used. " +
                    "For example, if asked about 'leadership', a document about 'commanding troops' or 'guiding a team' qualifies. " +
                    "If asked about 'innovation', a document describing 'a new invention' or 'a novel solution' qualifies. " +
                    "Name every file whose content meaningfully relates to the queried theme and briefly explain why.";
        } else {
            directive = "Be literal. Extrapolate nothing. If the exact phrase or fact is missing, state that it is not present.";
        }

        return ragPrompt.replace("<inferenceDirective>", directive);
    }
}
