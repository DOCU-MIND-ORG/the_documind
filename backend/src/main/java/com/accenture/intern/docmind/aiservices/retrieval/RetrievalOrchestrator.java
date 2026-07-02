package com.accenture.intern.docmind.aiservices.retrieval;

import com.accenture.intern.docmind.aiservices.understanding.*;
import com.accenture.intern.docmind.aiservices.understanding.plan.*;
import com.accenture.intern.docmind.dto.retrieval.RetrievalCandidate;
import com.accenture.intern.docmind.dto.chat.RetrievalResult;
import com.accenture.intern.docmind.dto.chat.VisualEvidence;
import com.accenture.intern.docmind.dto.chat.ProgressEvent;
import com.accenture.intern.docmind.dto.chat.ProgressStage;
import com.accenture.intern.docmind.dto.chat.ProgressStatus;
import com.accenture.intern.docmind.dto.chat.ExpansionReason;
import com.accenture.intern.docmind.config.RetrievalProperties;
import org.springframework.ai.document.Document;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class RetrievalOrchestrator {

    private final HybridRetrievalService hybridRetrievalService;
    private final RerankService rerankService;
    private final MultiSignalRanker multiSignalRanker;
    private final RetrievalProperties retrievalProperties;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public RetrievalOrchestrator(HybridRetrievalService hybridRetrievalService,
            RerankService rerankService, MultiSignalRanker multiSignalRanker,
            RetrievalProperties retrievalProperties, com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.hybridRetrievalService = hybridRetrievalService;
        this.rerankService = rerankService;
        this.multiSignalRanker = multiSignalRanker;
        this.retrievalProperties = retrievalProperties;
        this.objectMapper = objectMapper;
    }

    public Mono<RetrievalResult> orchestrate(String question, Long sessionId, ExecutionPlan execPlan, reactor.core.publisher.Sinks.Many<org.springframework.http.codec.ServerSentEvent<String>> progressSink) {
        if (progressSink != null) {
            java.util.Map<String, Object> meta = new java.util.HashMap<>();
            if (execPlan.getPlans() != null && !execPlan.getPlans().isEmpty()) {
                meta.put("scope", execPlan.getPlans().get(0).scope().toString());
            }
            String msg = new ProgressEvent(
                    ProgressStage.RETRIEVAL,
                    ProgressStatus.RUNNING,
                    "Searching documents...", null, null, meta).toJson(objectMapper);
            progressSink.tryEmitNext(org.springframework.http.codec.ServerSentEvent.<String>builder(msg).event("progress").build());
        }

        List<Mono<RetrievalResult>> retrieves = new ArrayList<>();
        
        for (RetrievalPlan plan : execPlan.getPlans()) {
            Mono<List<RetrievalCandidate>> planRetrievalMono = orchestratePlan(question, sessionId, plan, execPlan.getEntities(), progressSink);

            Mono<List<VisualEvidence>> visualMono = Mono.just(List.of());
            if (execPlan.isVisualSearch()) {
                String retrievalQuery = (plan.optimizedQuery() != null && !plan.optimizedQuery().isBlank()) ? plan.optimizedQuery() : question;
                boolean skipWholeDocument = plan.executionMode() != RetrievalExecutionMode.WHOLE_DOCUMENT;
                
                log.info("ORCHESTRATOR: Starting VISUAL PIPELINE | skipWholeDocument={} | sessionId={} | retrievalQuery='{}'",
                         skipWholeDocument, sessionId, retrievalQuery);
                         
                visualMono = hybridRetrievalService.retrieve(execPlan.getEntities(), retrievalQuery, sessionId, 15, skipWholeDocument, plan.targetDocuments(), true)
                    .map(docs -> rerankService.rerank(question, docs, 3, sessionId))
                    .map(docs -> multiSignalRanker.rank(docs, plan, execPlan.getEntities(), sessionId))
                    .map(docs -> docs.stream().limit(3).map(cand -> new VisualEvidence(
                            (String) cand.chunk().getMetadata().get("semanticId"),
                            null,
                            (String) cand.chunk().getMetadata().get("imageUrl"),
                            null,
                            cand.chunk().getText(),
                            cand.finalScore(),
                            (String) cand.chunk().getMetadata().get("sourceName")
                    )).collect(Collectors.toList()));
            }

            Mono<RetrievalResult> planRetrieval = planRetrievalMono
                .flatMap(primary -> fallbackQualityCheck(primary, plan, question, sessionId, execPlan.getEntities(), progressSink))
                .zipWith(visualMono)
                .map(tuple -> {
                    RetrievalResult result = tuple.getT1();
                    List<VisualEvidence> visuals = tuple.getT2();
                    List<RetrievalCandidate> attached = attachMetadata(result.evidence(), plan);
                    return new RetrievalResult(
                        attached, visuals, result.requestedScope(), result.actualScope(), result.expandedScope(), result.reason()
                    );
                })
                .onErrorResume(e -> {
                     log.error("Plan {} failed: {}", plan.purpose(), e.getMessage(), e);
                     return Mono.just(new RetrievalResult(List.of(), List.of(), plan.scope()));
                });
            retrieves.add(planRetrieval);
        }
        
        return Mono.zip(retrieves, results -> {
            List<RetrievalCandidate> combined = new ArrayList<>();
            List<VisualEvidence> combinedVisuals = new ArrayList<>();
            boolean anyExpanded = false;
            ExpansionReason reason = ExpansionReason.NONE;
            Scope reqScope = Scope.CORPUS;
            Scope actScope = Scope.CORPUS;
            
            for (Object r : results) {
                RetrievalResult res = (RetrievalResult) r;
                combined.addAll(res.evidence());
                combinedVisuals.addAll(res.visuals());
                if (res.expandedScope()) {
                    anyExpanded = true;
                    reason = res.reason();
                }
                reqScope = res.requestedScope();
                actScope = res.actualScope();
            }
            if (execPlan.getMergeOperation() == MergeOperation.UNION) {
                java.util.Set<String> seen = new java.util.HashSet<>();
                List<RetrievalCandidate> deduplicated = new ArrayList<>();
                for (RetrievalCandidate cand : combined) {
                    if (seen.add(cand.chunk().getId())) {
                        deduplicated.add(cand);
                    }
                }
                combined = deduplicated;
            } else if (execPlan.getMergeOperation() == MergeOperation.COMPARE) {
                // Keep everything to compare across sources
            }
            
            return new RetrievalResult(combined, combinedVisuals, reqScope, actScope, anyExpanded, reason);
        });
    }
    
    public RetrievalObservation generateObservation(List<RetrievalCandidate> candidates, RetrievalPlan plan) {
        if (candidates.isEmpty()) {
            return new RetrievalObservation(
                ObservationType.EVIDENCE_MISSING,
                "No results found for query: " + plan.optimizedQuery(),
                java.util.Collections.emptyList(),
                new RetrievalGap("Empty results", "Need terms related to " + plan.optimizedQuery())
            );
        }
        
        List<com.accenture.intern.docmind.dto.retrieval.RankingExplanation> explanations = candidates.stream()
            .map(RetrievalCandidate::explanation)
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toList());
            
        double topScore = candidates.get(0).finalScore();
        if (topScore > 0.8) {
            return new RetrievalObservation(
                ObservationType.EVIDENCE_FOUND,
                "Strong evidence found",
                explanations,
                null
            );
        } else if (topScore > 0.5) {
            return new RetrievalObservation(
                ObservationType.PARTIAL_EVIDENCE,
                "Partial/weak evidence found",
                explanations,
                new RetrievalGap("Low confidence results", "Requires broader search or different keywords")
            );
        } else {
            return new RetrievalObservation(
                ObservationType.OUT_OF_SCOPE,
                "Results are out of scope/unrelated",
                explanations,
                new RetrievalGap("Unrelated results", "Need to change search direction entirely")
            );
        }
    }

    private Mono<RetrievalResult> fallbackQualityCheck(List<RetrievalCandidate> primary, RetrievalPlan plan, String question, Long sessionId, List<EntityResolution> entities, reactor.core.publisher.Sinks.Many<org.springframework.http.codec.ServerSentEvent<String>> progressSink) {
        RetrievalQuality primaryQuality = evaluateQuality(primary);
        boolean shouldExpand = primary.isEmpty() || primaryQuality.getConfidence() < retrievalProperties.getPrimaryThreshold();
        
        if (!shouldExpand || plan.targetDocuments() == null || plan.targetDocuments().isEmpty()) {
            return Mono.just(new RetrievalResult(primary, List.of(), plan.scope()));
        }

        ExpansionReason reason = primary.isEmpty() ? 
            ExpansionReason.NO_RESULTS : 
            ExpansionReason.LOW_CONFIDENCE;
            
        log.info("[Planner]\nExecution = DIRECT\nTarget = {}\n?\n[Retriever]\nTopK = {} (Confidence: {})\n?\nScope Expansion (Reason: {})\n?\nGlobal\n?", 
            plan.targetDocuments(), primary.size(), primaryQuality.getConfidence(), reason);

        RetrievalPlan globalPlan = plan.withGlobalScope();
        
        if (progressSink != null) {
            String msg = "{\"type\":\"scope_expansion\",\"from\":\"TARGET_DOCUMENTS\",\"to\":\"GLOBAL_CORPUS\",\"reason\":\"No sufficient evidence\"}";
            progressSink.tryEmitNext(org.springframework.http.codec.ServerSentEvent.<String>builder(msg).event("scope_expansion").build());
        }
        
        return orchestratePlan(question, sessionId, globalPlan, entities, progressSink)
            .map(globalPrimary -> {
                log.info("TopK = {}\n?\nGeneration", globalPrimary.size());
                return new RetrievalResult(globalPrimary, List.of(), plan.scope(), globalPlan.scope(), true, reason);
            });
    }

    private RetrievalQuality evaluateQuality(List<RetrievalCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return new RetrievalQuality(0.0, 0.0, 0, 0);
        }

        double topScore = candidates.get(0).finalScore();
        double sumTop5 = 0;
        int countTop5 = Math.min(5, candidates.size());

        java.util.Set<String> uniqueDocs = new java.util.HashSet<>();

        for (int i = 0; i < candidates.size(); i++) {
            RetrievalCandidate cand = candidates.get(i);
            if (i < 5) {
                sumTop5 += cand.finalScore();
            }
            if (cand.chunk().getMetadata() != null && cand.chunk().getMetadata().containsKey("sourceName")) {
                uniqueDocs.add((String) cand.chunk().getMetadata().get("sourceName"));
            }
        }

        double averageTop5 = countTop5 > 0 ? sumTop5 / countTop5 : 0.0;
        return new RetrievalQuality(topScore, averageTop5, candidates.size(), uniqueDocs.size());
    }

    private List<RetrievalCandidate> attachMetadata(List<RetrievalCandidate> candidates, RetrievalPlan plan) {
        return candidates.stream().map(cand -> {
            java.util.Map<String, Object> newMetadata = new java.util.HashMap<>(cand.chunk().getMetadata());
            newMetadata.put("planId", plan.id());
            newMetadata.put("purpose", plan.purpose());
            Document newDoc = new Document(cand.chunk().getId(), cand.chunk().getText(), newMetadata);
            return new RetrievalCandidate(newDoc, cand.finalScore());
        }).collect(Collectors.toList());
    }

    private Mono<List<RetrievalCandidate>> orchestratePlan(String question, Long sessionId, RetrievalPlan plan, List<EntityResolution> entities, reactor.core.publisher.Sinks.Many<org.springframework.http.codec.ServerSentEvent<String>> progressSink) {
        String retrievalQuery = (plan.optimizedQuery() != null && !plan.optimizedQuery().isBlank()) ? plan.optimizedQuery() : question;
        boolean skipWholeDocument = plan.executionMode() != RetrievalExecutionMode.WHOLE_DOCUMENT;
        
        log.info("ORCHESTRATOR: skipWholeDocument={} | sessionId={} | retrievalQuery='{}' | executionMode={}",
                skipWholeDocument, sessionId, retrievalQuery, plan.executionMode());

        return hybridRetrievalService.retrieve(entities, retrievalQuery, sessionId, 15, skipWholeDocument, plan.targetDocuments(), false)
                .map(candidates -> rerankService.rerank(retrievalQuery, candidates, 5, sessionId))
                .doOnNext(reranked -> emitRanking(progressSink))
                .map(reranked -> multiSignalRanker.rank(reranked, plan, entities, sessionId));
    }

    private void emitRanking(reactor.core.publisher.Sinks.Many<org.springframework.http.codec.ServerSentEvent<String>> progressSink) {
        if (progressSink != null) {
            String msg = new ProgressEvent(
                    ProgressStage.RANKING,
                    ProgressStatus.RUNNING,
                    "Ranking evidence...", null, null, null).toJson(objectMapper);
            progressSink.tryEmitNext(org.springframework.http.codec.ServerSentEvent.<String>builder(msg).event("progress").build());
        }
    }
}