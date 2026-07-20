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

    private record PlanRetrievalResult(
        List<RetrievalCandidate> candidates,
        com.accenture.intern.docmind.dto.chat.RetrievalTelemetry telemetry
    ) {}

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

        if (execPlan.getPlans() == null || execPlan.getPlans().isEmpty()) {
            log.info("ORCHESTRATOR: Out of domain or empty plans. Bypassing retrieval.");
            return Mono.just(new RetrievalResult(List.of(), List.of(), 
                Scope.CORPUS, 
                Scope.CORPUS, 
                false, 
                ExpansionReason.NONE, 
                com.accenture.intern.docmind.dto.chat.RetrievalTelemetry.empty()));
        }

        List<Mono<RetrievalResult>> retrieves = new ArrayList<>();
        
        for (RetrievalPlan plan : execPlan.getPlans()) {
            log.info("ORCHESTRATOR: Executing Plan: Purpose='{}', Mode={}, Scope={}, Query='{}'", 
                     plan.purpose(), plan.executionMode(), plan.scope(), plan.optimizedQuery());
            Mono<PlanRetrievalResult> planRetrievalMono = orchestratePlan(question, sessionId, plan, execPlan.getEntities(), progressSink);

            Mono<RetrievalResult> planRetrieval = planRetrievalMono
                .flatMap(primary -> fallbackQualityCheck(primary, plan, question, sessionId, execPlan.getEntities(), progressSink))
                .flatMap(result -> {
                    List<RetrievalCandidate> attached = attachMetadata(result.getEvidence(), plan);
                    if (!execPlan.isVisualSearch() || attached.isEmpty()) {
                        return Mono.just(new RetrievalResult(attached, List.of(), result.getRequestedScope(), result.getActualScope(), result.isExpandedScope(), result.getReason(), result.getTelemetry()));
                    }

                    // Extract exact source names from final text result
                    List<String> finalSources = attached.stream()
                        .map(c -> (String) c.chunk().getMetadata().get("sourceName"))
                        .filter(java.util.Objects::nonNull)
                        .distinct()
                        .toList();

                    if (finalSources.isEmpty()) {
                        return Mono.just(new RetrievalResult(attached, List.of(), result.getRequestedScope(), result.getActualScope(), result.isExpandedScope(), result.getReason(), result.getTelemetry()));
                    }
                    
                    log.info("ORCHESTRATOR: Starting SEQUENTIAL VISUAL PIPELINE grounded to sources: {}", finalSources);

                    return Mono.fromCallable(() -> {
                        // 1. Extract unique sectionPaths from the cited text chunks
                        java.util.List<String> sectionPaths = attached.stream()
                            .map(cand -> (String) cand.chunk().getMetadata().get("sectionPath"))
                            .filter(java.util.Objects::nonNull)
                            .distinct()
                            .collect(Collectors.toList());

                        // 2. Tier 1: Same Section
                        String retrievalReason = "Same Section";
                        List<com.accenture.intern.docmind.entity.DocumentChunk> allImages;
                        if (!sectionPaths.isEmpty()) {
                            allImages = documentChunkRepository.findImages(finalSources, sectionPaths);
                        } else {
                            allImages = new java.util.ArrayList<>();
                        }

                        // 3. Tier 2: Same Document (Fallback)
                        if (allImages.isEmpty()) {
                            retrievalReason = "Same Document";
                            allImages = documentChunkRepository.findImages(finalSources, null);
                        }
                        
                        // 4. Sort images by proximity to the cited text chunks
                        String finalReason = retrievalReason;
                        java.util.Set<String> seenUrls = new java.util.HashSet<>();
                        List<VisualEvidence> visuals = allImages.stream()
                            .sorted(java.util.Comparator.comparingInt(img -> getMinDistance(img, attached)))
                            .filter(img -> {
                                String url = img.getImageUrl();
                                return url != null && seenUrls.add(url);
                            })
                            .limit(15)
                            .map(img -> new VisualEvidence(
                                img.getVectorId(),
                                null,
                                img.getImageUrl(),
                                null,
                                img.getContent(),
                                1.0, // Base visual score
                                img.getSourceName(),
                                img.getSectionPath(),
                                img.getPage(),
                                img.getHeading(),
                                finalReason
                            )).collect(Collectors.toList());
                        
                        
                        // We do not have VisualResult from repository directly, but we can capture visual metrics
                        com.accenture.intern.docmind.dto.chat.RetrievalTelemetry updatedTelemetry = result.getTelemetry().toBuilder()
                                .visualCandidates(allImages.size())
                                .visualTier(retrievalReason)
                                .visualLatency(0) // since it's immediate SQL we can leave 0 or measure it
                                .build();
                                
                        return new RetrievalResult(attached, visuals, result.getRequestedScope(), result.getActualScope(), result.isExpandedScope(), result.getReason(), updatedTelemetry);
                    }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
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
            
            com.accenture.intern.docmind.dto.chat.RetrievalTelemetry mergedTelemetry = com.accenture.intern.docmind.dto.chat.RetrievalTelemetry.empty();
            int planIndex = 0;
            for (Object r : results) {
                RetrievalResult res = (RetrievalResult) r;
                
                // Priority 2: Per-plan source distribution logging before merge
                RetrievalPlan loggedPlan = planIndex < execPlan.getPlans().size() ? execPlan.getPlans().get(planIndex) : null;
                if (loggedPlan != null) {
                    java.util.Map<String, Long> planSources = res.getEvidence().stream()
                        .map(c -> (String) c.chunk().getMetadata().get("sourceName"))
                        .filter(java.util.Objects::nonNull)
                        .collect(Collectors.groupingBy(s -> s, Collectors.counting()));
                    log.debug("MERGE [Plan {}] id={} | query='{}' | targets={} | chunks={} | sources={}",
                        planIndex + 1,
                        loggedPlan.id(),
                        loggedPlan.optimizedQuery(),
                        loggedPlan.targetDocuments(),
                        res.getEvidence().size(),
                        planSources);
                }
                planIndex++;
                
                combined.addAll(res.getEvidence());
                combinedVisuals.addAll(res.getVisuals());
                if (res.isExpandedScope()) {
                    anyExpanded = true;
                    reason = res.getReason();
                }
                reqScope = res.getRequestedScope();
                if (res.getActualScope() != reqScope) {
                    actScope = res.getActualScope();
                }
                mergedTelemetry = mergedTelemetry.mergeWith(res.getTelemetry());
            }
            
            // Priority 1: UNION dedup with before/after logging
            int preDedupeCount = combined.size();
            if (execPlan.getMergeOperation() == MergeOperation.UNION) {
                java.util.Set<String> seen = new java.util.HashSet<>();
                List<RetrievalCandidate> deduplicated = new ArrayList<>();
                for (RetrievalCandidate cand : combined) {
                    if (seen.add(cand.chunk().getId())) {
                        deduplicated.add(cand);
                    }
                }
                int duplicatesRemoved = preDedupeCount - deduplicated.size();
                log.debug("MERGE DEDUP: before={} | after={} | duplicates_removed={}",
                    preDedupeCount, deduplicated.size(), duplicatesRemoved);
                combined = deduplicated;
            } else if (execPlan.getMergeOperation() == MergeOperation.COMPARE) {
                // Keep everything to compare across sources
            }
            
            // Priority 1: Set finalHits ONCE after dedup — this is the authoritative count
            mergedTelemetry = mergedTelemetry.toBuilder()
                .finalHits(combined.size())
                .build();
            
            log.info("ORCHESTRATOR: Finished orchestrating all plans. Combined {} chunks and {} visuals (MergeOp: {})", 
                     combined.size(), combinedVisuals.size(), execPlan.getMergeOperation());
            
            return new RetrievalResult(combined, combinedVisuals, reqScope, actScope, anyExpanded, reason, mergedTelemetry);
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

    private Mono<RetrievalResult> fallbackQualityCheck(PlanRetrievalResult primary, RetrievalPlan plan, String question, Long sessionId, List<EntityResolution> entities, reactor.core.publisher.Sinks.Many<org.springframework.http.codec.ServerSentEvent<String>> progressSink) {
        RetrievalQuality primaryQuality = evaluateQuality(primary.candidates());
        boolean shouldExpand = primary.candidates().isEmpty() || primaryQuality.getConfidence() < retrievalProperties.getPrimaryThreshold();
        
        if (!shouldExpand || plan.targetDocuments() == null || plan.targetDocuments().isEmpty()) {
            return Mono.just(new RetrievalResult(primary.candidates(), List.of(), plan.scope(), plan.scope(), false, ExpansionReason.NONE, primary.telemetry()));
        }

        ExpansionReason reason = primary.candidates().isEmpty() ? 
            ExpansionReason.NO_RESULTS : 
            ExpansionReason.LOW_CONFIDENCE;
            
        log.info("[Planner]\nExecution = DIRECT\nTarget = {}\n?\n[Retriever]\nTopK = {} (Confidence: {})\n?\nScope Expansion (Reason: {})\n?\nGlobal\n?", 
            plan.targetDocuments(), primary.candidates().size(), primaryQuality.getConfidence(), reason);

        RetrievalPlan globalPlan = plan.withGlobalScope();
        
        if (progressSink != null) {
            String msg = "{\"type\":\"scope_expansion\",\"from\":\"TARGET_DOCUMENTS\",\"to\":\"GLOBAL_CORPUS\",\"reason\":\"No sufficient evidence\"}";
            progressSink.tryEmitNext(org.springframework.http.codec.ServerSentEvent.<String>builder(msg).event("scope_expansion").build());
        }
        
        return orchestratePlan(question, sessionId, globalPlan, entities, progressSink)
            .map(globalPrimary -> {
                log.info("TopK = {}\n?\nGeneration", globalPrimary.candidates().size());
                List<RetrievalCandidate> merged = new ArrayList<>(primary.candidates());
                java.util.Set<String> seenIds = primary.candidates().stream().map(c -> c.chunk().getId()).collect(Collectors.toSet());
                for (RetrievalCandidate gc : globalPrimary.candidates()) {
                    if (seenIds.add(gc.chunk().getId())) {
                        merged.add(gc);
                    }
                }
                merged.sort((a, b) -> Double.compare(b.finalScore(), a.finalScore()));
                return new RetrievalResult(merged, List.of(), plan.scope(), globalPlan.scope(), true, reason, primary.telemetry().mergeWith(globalPrimary.telemetry()));
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
            if (plan.executionMode() != null) {
                newMetadata.put("planType", plan.executionMode().name());
            }
            Document newDoc = new Document(cand.chunk().getId(), cand.chunk().getText(), newMetadata);
            return new RetrievalCandidate(newDoc, cand.finalScore());
        }).collect(Collectors.toList());
    }

    private Mono<PlanRetrievalResult> orchestratePlan(String question, Long sessionId, RetrievalPlan plan, List<EntityResolution> entities, reactor.core.publisher.Sinks.Many<org.springframework.http.codec.ServerSentEvent<String>> progressSink) {
        String retrievalQuery = (plan.optimizedQuery() != null && !plan.optimizedQuery().isBlank()) ? plan.optimizedQuery() : question;
        boolean skipWholeDocument = plan.executionMode() != RetrievalExecutionMode.WHOLE_DOCUMENT;
        
        log.info("ORCHESTRATOR: skipWholeDocument={} | sessionId={} | retrievalQuery='{}' | executionMode={} | isStructuralQuery={}",
                skipWholeDocument, sessionId, retrievalQuery, plan.executionMode(), plan.isStructuralQuery());

        int poolSize = determineAdaptivePoolSize(plan);

        Mono<com.accenture.intern.docmind.dto.chat.HybridRetrievalResult> baseRetrievalMono;
        
        if (plan.isStructuralQuery()) {
            baseRetrievalMono = hybridRetrievalService.retrieveDocumentStructure(plan.targetDocuments(), sessionId)
                .flatMap(structuralChunks -> {
                    boolean sectionMapRetrieved = !structuralChunks.isEmpty();
                    log.info("ORCHESTRATOR: isStructuralQuery=true, sectionMapRetrieved={}", sectionMapRetrieved);
                    if (sectionMapRetrieved) {
                        return Mono.just(new com.accenture.intern.docmind.dto.chat.HybridRetrievalResult(structuralChunks, structuralChunks.size(), 0, structuralChunks.size(), 0, 0, 0));
                    } else {
                        // Fallback to normal retrieval if no structural chunk is found
                        return hybridRetrievalService.retrieve(entities, retrievalQuery, sessionId, poolSize, skipWholeDocument, plan.targetDocuments(), false, null);
                    }
                });
        } else {
            baseRetrievalMono = hybridRetrievalService.retrieve(entities, retrievalQuery, sessionId, poolSize, skipWholeDocument, plan.targetDocuments(), false, null);
        }


        var telemetryBuilder = com.accenture.intern.docmind.dto.chat.RetrievalTelemetry.builder();
        long planStart = System.currentTimeMillis();

        Mono<List<RetrievalCandidate>> candidatesMono = baseRetrievalMono
                .flatMap(hybridResult -> {
                    telemetryBuilder.denseHits(hybridResult.denseHits());
                    telemetryBuilder.keywordHits(hybridResult.keywordHits());
                    telemetryBuilder.rrfHits(hybridResult.rrfHits());
                    telemetryBuilder.denseLatency(hybridResult.denseLatency());
                    telemetryBuilder.keywordLatency(hybridResult.keywordLatency());
                    telemetryBuilder.fusionLatency(hybridResult.fusionLatency());

                    List<RetrievalCandidate> candidates = hybridResult.candidates();
                    boolean isOrderPreserved = candidates.stream().anyMatch(c ->
                            "WHOLE_DOCUMENT".equals(c.chunk().getMetadata().get("retrievalModeUsed"))
                            || "CONTIGUOUS".equals(c.chunk().getMetadata().get("retrievalModeUsed")));
                    if (isOrderPreserved) {
                        return Mono.just(new com.accenture.intern.docmind.dto.chat.RerankResult(candidates, candidates.size(), 0)); 
                    }
                    return rerankService.rerank(retrievalQuery, candidates, 15, sessionId);
                })
                .doOnNext(rerankResult -> emitRanking(progressSink))
                .map(rerankResult -> {
                    telemetryBuilder.afterRerankHits(rerankResult.afterRerankHits());
                    telemetryBuilder.rerankLatency(rerankResult.latency());

                    List<RetrievalCandidate> reranked = rerankResult.candidates();
                    java.util.List<String> candidateDocs = reranked.stream()
                            .map(c -> (String) c.chunk().getMetadata().get("sourceName"))
                            .filter(java.util.Objects::nonNull)
                            .distinct()
                            .collect(Collectors.toList());
                    telemetryBuilder.retrievalCandidateDocs(candidateDocs);

                    boolean isOrderPreserved = reranked.stream().anyMatch(c ->
                            "WHOLE_DOCUMENT".equals(c.chunk().getMetadata().get("retrievalModeUsed"))
                            || "CONTIGUOUS".equals(c.chunk().getMetadata().get("retrievalModeUsed")));
                    if (isOrderPreserved) {
                        return reranked;
                    }
                    List<RetrievalCandidate> ranked = multiSignalRanker.rank(reranked, plan, entities, sessionId);

                    // Apply thresholding and fallback floor
                    double threshold = 0.005;
                    List<RetrievalCandidate> finalCandidates = new java.util.ArrayList<>();
                    List<RetrievalCandidate> rejectedCandidates = new java.util.ArrayList<>();

                    for (RetrievalCandidate c : ranked) {
                        Object isImageObj = c.chunk().getMetadata().get("isImage");
                        boolean isImage = Boolean.TRUE.equals(isImageObj) || "true".equals(String.valueOf(isImageObj).toLowerCase());
                        if (c.finalScore() >= threshold || isImage) {
                            finalCandidates.add(c);
                        } else {
                            rejectedCandidates.add(c);
                        }
                    }

                    if (plan.scope() == Scope.CORPUS && (plan.targetDocuments() == null || plan.targetDocuments().isEmpty())) {
                        int minResults = 3;
                        if (finalCandidates.size() < minResults) {
                            log.info("ORCHESTRATOR: CONCEPT_EXPANSION floor triggered. Survivors={}, Minimum={}. Attempting to rescue...", finalCandidates.size(), minResults);
                            java.util.Set<String> representedSources = finalCandidates.stream()
                                    .map(c -> (String) c.chunk().getMetadata().get("sourceName"))
                                    .filter(java.util.Objects::nonNull)
                                    .collect(Collectors.toSet());

                            for (RetrievalCandidate rejected : rejectedCandidates) {
                                if (finalCandidates.size() >= minResults) break;
                                String source = (String) rejected.chunk().getMetadata().get("sourceName");
                                if (source != null && !representedSources.contains(source) && rejected.finalScore() >= 0.05) {
                                    finalCandidates.add(rejected);
                                    representedSources.add(source);
                                    log.info("ORCHESTRATOR: Rescued chunk from '{}' (Score: {})", source, rejected.finalScore());
                                }
                            }
                        }
                    }

                    telemetryBuilder.afterMultiSignalHits(finalCandidates.size());
                    return finalCandidates;
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
                    if (isOrderPreserved) {
                        return Mono.just(ranked);
                    } else {
                        return expandContext(ranked).doOnNext(expanded -> telemetryBuilder.contextExpandedHits(expanded.size()));
                    }
                });
                
        return candidatesMono.map(finalCandidates -> {
            telemetryBuilder.finalHits(finalCandidates.size());
            telemetryBuilder.plannerReason(plan.purpose());
            telemetryBuilder.plannerLatency(System.currentTimeMillis() - planStart);
            
            String path = plan.executionMode() == RetrievalExecutionMode.WHOLE_DOCUMENT ? "WHOLE_DOCUMENT" 
                        : plan.executionMode() == RetrievalExecutionMode.CONTIGUOUS ? "CONTIGUOUS" 
                        : "RANKED -> Rerank -> MultiSignal";
            telemetryBuilder.executionPath(path);
            return new PlanRetrievalResult(finalCandidates, telemetryBuilder.build());
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

    private int determineAdaptivePoolSize(RetrievalPlan plan) {
        if (plan.targetDocuments() != null && plan.targetDocuments().size() > 1) {
            return Math.min(80, Math.max(40, plan.targetDocuments().size() * 12));
        } else if (plan.targetDocuments() == null || plan.targetDocuments().isEmpty()) {
            return 60; // Wider net for Concept Expansion and untargeted global searches
        }
        return 30;
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
                if (idxObj instanceof Number number) {
                    idx = number.intValue();
                } else if (idxObj instanceof String s) {
                    try { idx = Integer.parseInt(s); } catch (Exception ignored) {}
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

    private int getMinDistance(com.accenture.intern.docmind.entity.DocumentChunk img, java.util.List<com.accenture.intern.docmind.dto.retrieval.RetrievalCandidate> textChunks) {
        if (img.getChunkIndex() == null) return Integer.MAX_VALUE;
        int imgIndex = img.getChunkIndex();
        int min = Integer.MAX_VALUE;
        for (com.accenture.intern.docmind.dto.retrieval.RetrievalCandidate cand : textChunks) {
            Object obj = cand.chunk().getMetadata().get("chunkIndex");
            int textIndex = -1;
            if (obj instanceof Number number) {
                textIndex = number.intValue();
            } else if (obj instanceof String s) {
                try {
                    textIndex = Integer.parseInt(s);
                } catch (NumberFormatException ignored) {}
            }
            
            if (textIndex >= 0) {
                int dist = Math.abs(imgIndex - textIndex);
                if (dist < min) {
                    min = dist;
                }
            }
        }
        return min;
    }
}
