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
    private final com.accenture.intern.docmind.repository.DocumentChunkRepository documentChunkRepository;

    public RetrievalOrchestrator(HybridRetrievalService hybridRetrievalService,
            RerankService rerankService, MultiSignalRanker multiSignalRanker,
            RetrievalProperties retrievalProperties, com.fasterxml.jackson.databind.ObjectMapper objectMapper,
            com.accenture.intern.docmind.repository.DocumentChunkRepository documentChunkRepository) {
        this.hybridRetrievalService = hybridRetrievalService;
        this.rerankService = rerankService;
        this.multiSignalRanker = multiSignalRanker;
        this.retrievalProperties = retrievalProperties;
        this.objectMapper = objectMapper;
        this.documentChunkRepository = documentChunkRepository;
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
            log.info("ORCHESTRATOR: Executing Plan: Purpose='{}', Mode={}, Scope={}, Query='{}'", 
                     plan.purpose(), plan.executionMode(), plan.scope(), plan.optimizedQuery());
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
                    .map(docs -> {
                        java.util.Set<String> seenUrls = new java.util.HashSet<>();
                        return docs.stream()
                            .filter(cand -> {
                                String url = (String) cand.chunk().getMetadata().get("imageUrl");
                                return url == null || seenUrls.add(url);
                            })
                            .limit(3)
                            .map(cand -> new VisualEvidence(
                                (String) cand.chunk().getMetadata().get("semanticId"),
                                null,
                                (String) cand.chunk().getMetadata().get("imageUrl"),
                                null,
                                cand.chunk().getText(),
                                cand.finalScore(),
                                (String) cand.chunk().getMetadata().get("sourceName")
                            )).collect(Collectors.toList());
                    });
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
            
            
            log.info("ORCHESTRATOR: Finished orchestrating all plans. Combined {} chunks and {} visuals (MergeOp: {})", 
                     combined.size(), combinedVisuals.size(), execPlan.getMergeOperation());
            
            return new RetrievalResult(combined, combinedVisuals, reqScope, actScope, anyExpanded, reason);
        });
    }
    
    public RetrievalObservation generateObservation(List<RetrievalCandidate> candidates, RetrievalPlan plan, List<RetrievalCandidate> previousCandidates) {
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
        
        // Coverage tracking for ADAPTIVE completeness checks
        java.util.Set<String> newSections = new java.util.HashSet<>();
        int chunksWithSections = 0;
        for (RetrievalCandidate c : candidates) {
            String sp = (String) c.chunk().getMetadata().get("sectionPath");
            if (sp != null && !sp.isBlank()) {
                newSections.add(sp);
                chunksWithSections++;
            }
        }
        
        java.util.Set<String> oldSections = new java.util.HashSet<>();
        if (previousCandidates != null) {
            for (RetrievalCandidate c : previousCandidates) {
                String sp = (String) c.chunk().getMetadata().get("sectionPath");
                if (sp != null && !sp.isBlank()) {
                    oldSections.add(sp);
                }
            }
        }
        
        int totalSectionsBefore = oldSections.size();
        newSections.removeAll(oldSections);
        int newlyDiscovered = newSections.size();
        
        // Only report coverage if the document has structural metadata
        String coverageMsg = "";
        if (chunksWithSections > 0) {
            coverageMsg = String.format(" (Found %d new sections. Previous sections: %d)", newlyDiscovered, totalSectionsBefore);
        }
        
        if (topScore > 0.8) {
            return new RetrievalObservation(
                ObservationType.EVIDENCE_FOUND,
                "Strong evidence found" + coverageMsg,
                explanations,
                null
            );
        } else if (topScore > 0.5) {
            return new RetrievalObservation(
                ObservationType.PARTIAL_EVIDENCE,
                "Partial/weak evidence found" + coverageMsg,
                explanations,
                new RetrievalGap("Low confidence results", "Requires broader search or different keywords")
            );
        } else {
            return new RetrievalObservation(
                ObservationType.OUT_OF_SCOPE,
                "Results are out of scope/unrelated" + coverageMsg,
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
        
        log.info("ORCHESTRATOR: skipWholeDocument={} | sessionId={} | retrievalQuery='{}' | executionMode={} | isStructuralQuery={}",
                skipWholeDocument, sessionId, retrievalQuery, plan.executionMode(), plan.isStructuralQuery());

        Mono<List<RetrievalCandidate>> baseRetrievalMono;
        
        if (plan.isStructuralQuery()) {
            baseRetrievalMono = hybridRetrievalService.retrieveDocumentStructure(plan.targetDocuments(), sessionId)
                .flatMap(structuralChunks -> {
                    boolean sectionMapRetrieved = !structuralChunks.isEmpty();
                    log.info("ORCHESTRATOR: isStructuralQuery=true, sectionMapRetrieved={}", sectionMapRetrieved);
                    if (sectionMapRetrieved) {
                        return Mono.just(structuralChunks);
                    } else {
                        // Fallback to normal retrieval if no structural chunk is found
                        return hybridRetrievalService.retrieve(entities, retrievalQuery, sessionId, 15, skipWholeDocument, plan.targetDocuments(), false);
                    }
                });
        } else {
            baseRetrievalMono = hybridRetrievalService.retrieve(entities, retrievalQuery, sessionId, 15, skipWholeDocument, plan.targetDocuments(), false);
        }


        return baseRetrievalMono
                .map(candidates -> {
                    boolean isOrderPreserved = candidates.stream().anyMatch(c ->
                            "WHOLE_DOCUMENT".equals(c.chunk().getMetadata().get("retrievalModeUsed"))
                            || "CONTIGUOUS".equals(c.chunk().getMetadata().get("retrievalModeUsed")));
                    if (isOrderPreserved) {
                        return candidates; // Bypass reranking: order was already set during retrieval
                    }
                    return rerankService.rerank(retrievalQuery, candidates, 5, sessionId);
                })
                .doOnNext(reranked -> emitRanking(progressSink))
                .map(reranked -> {
                    boolean isOrderPreserved = reranked.stream().anyMatch(c ->
                            "WHOLE_DOCUMENT".equals(c.chunk().getMetadata().get("retrievalModeUsed"))
                            || "CONTIGUOUS".equals(c.chunk().getMetadata().get("retrievalModeUsed")));
                    if (isOrderPreserved) {
                        return reranked; // Bypass multi-signal ranker: preserve reading order
                    }
                    return multiSignalRanker.rank(reranked, plan, entities, sessionId);
                })
                .flatMap(ranked -> {
                    if (plan.executionMode() == RetrievalExecutionMode.CONTIGUOUS) {
                        return hybridRetrievalService.expandContiguous(ranked, retrievalQuery, sessionId, plan.targetDocuments());
                    }
                    return Mono.just(ranked);
                })
                .map(ranked -> {
                    if (plan.isStructuralQuery()) {
                        boolean sectionMapIncluded = ranked.stream().anyMatch(c -> Boolean.TRUE.equals(c.chunk().getMetadata().get("structuralMatch")));
                        log.info("ORCHESTRATOR: sectionMapIncluded={}", sectionMapIncluded);
                    }
                    return ranked;
                })
                .flatMap(ranked -> {
                    boolean isOrderPreserved = ranked.stream().anyMatch(c ->
                            "WHOLE_DOCUMENT".equals(c.chunk().getMetadata().get("retrievalModeUsed"))
                            || "CONTIGUOUS".equals(c.chunk().getMetadata().get("retrievalModeUsed")));
                    return isOrderPreserved ? Mono.just(ranked) : expandContext(ranked);
                });
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

    private Mono<List<RetrievalCandidate>> expandContext(List<RetrievalCandidate> candidates) {
        if (candidates.isEmpty()) return Mono.just(candidates);

        return Mono.fromCallable(() -> {
            List<RetrievalCandidate> expanded = new ArrayList<>();
            for (RetrievalCandidate cand : candidates) {
                if (cand.chunk().getMetadata() == null) {
                    expanded.add(cand);
                    continue;
                }
                String sourceName = (String) cand.chunk().getMetadata().get("sourceName");
                Object idxObj = cand.chunk().getMetadata().get("chunkIndex");
                if (sourceName == null || idxObj == null) {
                    expanded.add(cand);
                    continue;
                }
                
                int idx = -1;
                if (idxObj instanceof Integer) {
                    idx = (Integer) idxObj;
                } else if (idxObj instanceof String) {
                    try { idx = Integer.parseInt((String) idxObj); } catch (Exception ignored) {}
                }
                
                if (idx < 0) {
                    expanded.add(cand);
                    continue;
                }

                List<Integer> neighborsToFetch = java.util.List.of(idx - 1, idx + 1);
                List<com.accenture.intern.docmind.entity.DocumentChunk> neighbors = documentChunkRepository.findBySourceNameAndChunkIndexIn(sourceName, neighborsToFetch);
                
                if (neighbors.isEmpty()) {
                    expanded.add(cand);
                    continue;
                }
                
                String prevText = "";
                String nextText = "";
                for (com.accenture.intern.docmind.entity.DocumentChunk n : neighbors) {
                    if (n.getChunkIndex() != null) {
                        if (n.getChunkIndex() == idx - 1) prevText = "[Previous Context]:\\n" + n.getContent() + "\\n\\n";
                        if (n.getChunkIndex() == idx + 1) nextText = "\\n\\n[Next Context]:\\n" + n.getContent();
                    }
                }
                
                String newText = prevText + cand.chunk().getText() + nextText;
                
                java.util.Map<String, Object> newMeta = new java.util.HashMap<>(cand.chunk().getMetadata());
                newMeta.put("contextExpanded", true);
                
                Document newDoc = new Document(cand.chunk().getId(), newText, newMeta);
                expanded.add(new RetrievalCandidate(newDoc, cand.semanticScore(), cand.entityScore(),
                        cand.metadataScore(), cand.sessionScore(), cand.finalScore(),
                        cand.matchedEntities(), cand.explanation()));
            }
            return expanded;
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }
}