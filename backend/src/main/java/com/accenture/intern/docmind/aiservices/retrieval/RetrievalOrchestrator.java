package com.accenture.intern.docmind.aiservices.retrieval;

import com.accenture.intern.docmind.aiservices.understanding.*;
import com.accenture.intern.docmind.aiservices.understanding.plan.StaticExecutionPlan;
import com.accenture.intern.docmind.dto.retrieval.RetrievalCandidate;
import com.accenture.intern.docmind.config.RetrievalProperties;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class RetrievalOrchestrator {

    private static final int MAX_MULTI_SOURCE_CHUNKS = 6;
    private static final int MULTI_SOURCE_POOL_SIZE = 30;

    private final HybridRetrievalService hybridRetrievalService;
    private final RerankService rerankService;
    private final MultiSignalRanker multiSignalRanker;
    private final VectorStoreService vectorStoreService;
    private final MessagesPineconeVectorStore messagesVectorStore;
    private final RetrievalProperties retrievalProperties;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public RetrievalOrchestrator(HybridRetrievalService hybridRetrievalService,
            RerankService rerankService, MultiSignalRanker multiSignalRanker,
            VectorStoreService vectorStoreService, MessagesPineconeVectorStore messagesVectorStore,
            RetrievalProperties retrievalProperties, com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.hybridRetrievalService = hybridRetrievalService;
        this.rerankService = rerankService;
        this.multiSignalRanker = multiSignalRanker;
        this.vectorStoreService = vectorStoreService;
        this.messagesVectorStore = messagesVectorStore;
        this.retrievalProperties = retrievalProperties;
        this.objectMapper = objectMapper;
    }

    public Mono<List<RetrievalCandidate>> orchestrate(String question, Long sessionId, StaticExecutionPlan execPlan, reactor.core.publisher.Sinks.Many<org.springframework.http.codec.ServerSentEvent<String>> progressSink) {
        if (progressSink != null) {
            String msg = new com.accenture.intern.docmind.dto.chat.ProgressEvent(
                    com.accenture.intern.docmind.dto.chat.ProgressStage.RETRIEVAL,
                    com.accenture.intern.docmind.dto.chat.ProgressStatus.RUNNING,
                    "Searching documents...", null, null, null).toJson(objectMapper);
            progressSink.tryEmitNext(org.springframework.http.codec.ServerSentEvent.<String>builder(msg).event("progress").build());
        }

        List<Mono<List<RetrievalCandidate>>> retrieves = new ArrayList<>();
        for (RetrievalPlan plan : execPlan.plans()) {
            Mono<List<RetrievalCandidate>> planRetrieval = orchestratePlan(question, sessionId, plan, progressSink)
                .flatMap(primary -> fallbackQualityCheck(primary, plan, question, sessionId))
                .map(candidates -> attachMetadata(candidates, plan))
                .onErrorResume(e -> {
                     log.error("Plan {} failed: {}", plan.purpose(), e.getMessage(), e);
                     return Mono.just(List.of());
                });
            retrieves.add(planRetrieval);
        }
        
        return Mono.zip(retrieves, results -> {
            List<RetrievalCandidate> combined = new ArrayList<>();
            for (Object r : results) {
                @SuppressWarnings("unchecked")
                List<RetrievalCandidate> res = (List<RetrievalCandidate>) r;
                combined.addAll(res);
            }
            if (execPlan.mergeOperation() == MergeOperation.UNION) {
                java.util.Set<String> seen = new java.util.HashSet<>();
                List<RetrievalCandidate> deduplicated = new ArrayList<>();
                for (RetrievalCandidate cand : combined) {
                    if (seen.add(cand.chunk().getId())) {
                        deduplicated.add(cand);
                    }
                }
                combined = deduplicated;
            }
            return combined;
        });
    }
    
    public com.accenture.intern.docmind.aiservices.understanding.plan.RetrievalObservation generateObservation(List<RetrievalCandidate> candidates, RetrievalPlan plan) {
        if (candidates.isEmpty()) {
            return new com.accenture.intern.docmind.aiservices.understanding.plan.RetrievalObservation(
                com.accenture.intern.docmind.aiservices.understanding.plan.ObservationType.EVIDENCE_MISSING,
                "No results found for query: " + plan.optimizedQuery(),
                java.util.Collections.emptyList(),
                new com.accenture.intern.docmind.aiservices.understanding.plan.RetrievalGap("Empty results", "Need terms related to " + plan.optimizedQuery())
            );
        }
        
        List<com.accenture.intern.docmind.dto.retrieval.RankingExplanation> explanations = candidates.stream()
            .map(RetrievalCandidate::explanation)
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toList());
            
        double topScore = candidates.get(0).finalScore();
        if (topScore > 0.8) {
            return new com.accenture.intern.docmind.aiservices.understanding.plan.RetrievalObservation(
                com.accenture.intern.docmind.aiservices.understanding.plan.ObservationType.EVIDENCE_FOUND,
                "Strong evidence found",
                explanations,
                null
            );
        } else if (topScore > 0.5) {
            return new com.accenture.intern.docmind.aiservices.understanding.plan.RetrievalObservation(
                com.accenture.intern.docmind.aiservices.understanding.plan.ObservationType.PARTIAL_EVIDENCE,
                "Partial/weak evidence found",
                explanations,
                new com.accenture.intern.docmind.aiservices.understanding.plan.RetrievalGap("Low confidence results", "Requires broader search or different keywords")
            );
        } else {
            return new com.accenture.intern.docmind.aiservices.understanding.plan.RetrievalObservation(
                com.accenture.intern.docmind.aiservices.understanding.plan.ObservationType.OUT_OF_SCOPE,
                "Results are out of scope/unrelated",
                explanations,
                new com.accenture.intern.docmind.aiservices.understanding.plan.RetrievalGap("Unrelated results", "Need to change search direction entirely")
            );
        }
    }

    private Mono<List<RetrievalCandidate>> fallbackQualityCheck(List<RetrievalCandidate> primary, RetrievalPlan plan, String question, Long sessionId) {
        if (plan.retrievalStrategy() == RetrievalStrategy.MULTI_SOURCE || plan.retrievalStrategy() == RetrievalStrategy.META_DOC_SEARCH) {
            return Mono.just(primary);
        }

        RetrievalQuality primaryQuality = evaluateQuality(primary);
        if (primaryQuality.getConfidence() >= retrievalProperties.getPrimaryThreshold()) {
            log.info("[Retrieval] Plan '{}' Primary Confidence={%.2f} >= threshold.", plan.purpose(), primaryQuality.getConfidence());
            return Mono.just(primary);
        }

        log.info("[Retrieval] Plan '{}' Primary Confidence={%.2f} < threshold. Triggering Session Fallback.", plan.purpose(), primaryQuality.getConfidence());
        
        return vectorStoreService.retrieveSessionFallback(plan.optimizedQuery(), 20, sessionId)
            .map(docs -> docs.stream().map(d -> new RetrievalCandidate(d, 0.0)).collect(Collectors.toList()))
            .flatMap(sessionResults -> {
                List<RetrievalCandidate> rerankedSession = rerankService.rerank(plan.optimizedQuery(), sessionResults, 20, sessionId, plan.retrievalStrategy());
                List<RetrievalCandidate> mergedPrimaryAndSession = new ArrayList<>(primary);
                mergedPrimaryAndSession.addAll(rerankedSession);
                
                RetrievalQuality sessionMergedQuality = evaluateQuality(mergedPrimaryAndSession);
                
                if (sessionMergedQuality.getConfidence() >= retrievalProperties.getSessionThreshold()) {
                    log.info("[Retrieval] Plan '{}' Session Fallback Confidence={%.2f} >= threshold.", plan.purpose(), sessionMergedQuality.getConfidence());
                    return Mono.just(mergedPrimaryAndSession);
                }

                log.info("[Retrieval] Plan '{}' Session Fallback Confidence={%.2f} < threshold. Triggering Memory Fallback.", plan.purpose(), sessionMergedQuality.getConfidence());
                List<Document> memoryChunksDocs = messagesVectorStore.similaritySearch(
                    SearchRequest.builder().query(question).topK(10)
                        .filterExpression(new FilterExpressionBuilder().eq("sessionId", sessionId.toString()).build())
                        .build());
                List<RetrievalCandidate> memoryChunks = memoryChunksDocs.stream().map(d -> new RetrievalCandidate(d, 0.0)).collect(Collectors.toList());
                
                List<RetrievalCandidate> finalMerged = new ArrayList<>(mergedPrimaryAndSession);
                finalMerged.addAll(memoryChunks);
                return Mono.just(finalMerged);
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

    private Mono<List<RetrievalCandidate>> orchestratePlan(String question, Long sessionId, RetrievalPlan RetrievalPlan, reactor.core.publisher.Sinks.Many<org.springframework.http.codec.ServerSentEvent<String>> progressSink) {
        RetrievalStrategy strategy = RetrievalPlan.retrievalStrategy();

        String retrievalQuery = (RetrievalPlan.optimizedQuery() != null && !RetrievalPlan.optimizedQuery().isBlank())
                        ? RetrievalPlan.optimizedQuery() : question;

        boolean skipWholeDocument = RetrievalPlan.executionMode() != RetrievalExecutionMode.WHOLE_DOCUMENT;

        log.info("ORCHESTRATOR: strategy={} | skipWholeDocument={} | sessionId={} | retrievalQuery='{}' | executionMode={}",
                strategy, skipWholeDocument, sessionId, retrievalQuery, RetrievalPlan.executionMode());

        return switch (strategy) {
            case MULTI_SOURCE -> multiSourcePipeline(question, retrievalQuery, sessionId, RetrievalPlan, skipWholeDocument, progressSink);
            case META_DOC_SEARCH -> metaDocPipeline(retrievalQuery, sessionId, skipWholeDocument, RetrievalPlan, progressSink);
            case CONCEPT_EXPANSION -> conceptExpansionPipeline(question, retrievalQuery, sessionId, RetrievalPlan, skipWholeDocument, progressSink);
            default -> singleSourcePipeline(question, retrievalQuery, sessionId, RetrievalPlan, skipWholeDocument, progressSink);
        };
    }

    private void emitRanking(reactor.core.publisher.Sinks.Many<org.springframework.http.codec.ServerSentEvent<String>> progressSink) {
        if (progressSink != null) {
            String msg = new com.accenture.intern.docmind.dto.chat.ProgressEvent(
                    com.accenture.intern.docmind.dto.chat.ProgressStage.RANKING,
                    com.accenture.intern.docmind.dto.chat.ProgressStatus.RUNNING,
                    "Ranking evidence...", null, null, null).toJson(objectMapper);
            progressSink.tryEmitNext(org.springframework.http.codec.ServerSentEvent.<String>builder(msg).event("progress").build());
        }
    }

    // ==================================================================================
    // SINGLE_SOURCE
    // ==================================================================================

    private Mono<List<RetrievalCandidate>> singleSourcePipeline(String question, String retrievalQuery,
            Long sessionId, RetrievalPlan decision, boolean skipWholeDocument, reactor.core.publisher.Sinks.Many<org.springframework.http.codec.ServerSentEvent<String>> progressSink) {
        return hybridRetrievalService.retrieve(decision.entities(), retrievalQuery, sessionId, 15, skipWholeDocument, decision.targetDocuments())
                .map(candidates -> rerankService.rerank(
                        retrievalQuery, candidates, 5, sessionId, RetrievalStrategy.SINGLE_SOURCE))
                .doOnNext(reranked -> emitRanking(progressSink))
                .map(reranked -> multiSignalRanker.rank(reranked, decision, sessionId));
    }

    // ==================================================================================
    // MULTI_SOURCE
    // ==================================================================================

    private Mono<List<RetrievalCandidate>> multiSourcePipeline(String question, String retrievalQuery,
            Long sessionId, RetrievalPlan decision, boolean skipWholeDocument, reactor.core.publisher.Sinks.Many<org.springframework.http.codec.ServerSentEvent<String>> progressSink) {

        List<ComparisonTarget> comparisons = decision.comparisons();

        if (comparisons == null || comparisons.size() < 2) {
            log.warn("ORCHESTRATOR: MULTI_SOURCE fallback to SINGLE_SOURCE — fewer than 2 comparison targets");
            return singleSourcePipeline(question, retrievalQuery, sessionId, decision, skipWholeDocument, progressSink);
        }

        int chunksPerEntity = Math.max(1, MAX_MULTI_SOURCE_CHUNKS / comparisons.size());
        log.info("ORCHESTRATOR: MULTI_SOURCE asymmetric — targets={}, chunksPerEntity={}, poolSize={}",
                comparisons.size(), chunksPerEntity, MULTI_SOURCE_POOL_SIZE);

        return hybridRetrievalService.retrieve(decision.entities(), retrievalQuery, sessionId, MULTI_SOURCE_POOL_SIZE, skipWholeDocument, decision.targetDocuments())
                .flatMap(widePool -> {
                    java.util.Map<String, List<RetrievalCandidate>> chunksBySource = new java.util.LinkedHashMap<>();
                    for (RetrievalCandidate cand : widePool) {
                        String src = ((String) cand.chunk().getMetadata()
                                .getOrDefault("sourceName", "")).toLowerCase();
                        chunksBySource.computeIfAbsent(src, k -> new ArrayList<>()).add(cand);
                    }

                    List<Mono<List<RetrievalCandidate>>> processingStages = new ArrayList<>();

                    for (ComparisonTarget target : comparisons) {
                        // chunksBySource keys come from chunk metadata "sourceName", which is
                        // always normalized (extension stripped, underscores -> spaces, see
                        // FilenameNormalizer). target.entity() may instead be the raw filename
                        // the LLM resolved (e.g. "Jio_5G_Rollout_Project.pdf"); normalizing it
                        // here too keeps the comparison apples-to-apples so a document that's
                        // actually sitting right there in the pool isn't wrongly reported as
                        // "diluted out" just because of formatting differences.
                        String normalizedEntityToken = com.accenture.intern.docmind.util.FilenameNormalizer.normalize(target.entity());
                        final String entityToken = normalizedEntityToken.isBlank()
                                ? target.entity().toLowerCase()
                                : normalizedEntityToken;
                        String[] entityTokens = entityToken.split("[^a-z0-9]+");
                        
                        String matchedSourceFile = chunksBySource.keySet().stream()
                                .filter(fileName -> {
                                    if (fileName.contains(entityToken)) return true;
                                    
                                    List<String> validTokens = java.util.Arrays.stream(entityTokens)
                                            .filter(t -> t.length() > 2)
                                            .collect(Collectors.toList());
                                            
                                    if (!validTokens.isEmpty()) {
                                        return validTokens.stream().allMatch(fileName::contains);
                                    }
                                    return false;
                                })
                                .findFirst()
                                .orElse(null);

                        if (matchedSourceFile != null) {
                            List<RetrievalCandidate> topChunks = chunksBySource.get(matchedSourceFile).stream()
                                    .limit(chunksPerEntity)
                                    .collect(Collectors.toList());
                            log.info("ORCHESTRATOR: FAST PATH — Entity '{}' FOUND in pool ({} chunks taken)",
                                    target.entity(), topChunks.size());
                            processingStages.add(Mono.just(topChunks));
                        } else {
                            log.warn("ORCHESTRATOR: SLOW PATH — Entity '{}' diluted out. Executing fallback: '{}'",
                                    target.entity(), target.optimizedQuery());

                            Mono<List<RetrievalCandidate>> fallbackSearch = hybridRetrievalService
                                    .retrieve(decision.entities(), target.optimizedQuery(), sessionId, 15, skipWholeDocument, decision.targetDocuments())
                                    .map(fallbackPool -> {
                                        List<RetrievalCandidate> filtered = fallbackPool.stream()
                                                .filter(cand -> {
                                                    String src = ((String) cand.chunk().getMetadata()
                                                            .getOrDefault("sourceName", "")).toLowerCase();
                                                    String text = cand.chunk().getText() != null
                                                            ? cand.chunk().getText().toLowerCase()
                                                            : "";
                                                    return src.contains(entityToken) || text.contains(entityToken);
                                                })
                                                .limit(chunksPerEntity)
                                                .collect(Collectors.toList());
                                        log.info("ORCHESTRATOR: fallback for '{}' yielded {} specific chunks",
                                                target.entity(), filtered.size());
                                        return filtered;
                                    });
                            processingStages.add(fallbackSearch);
                        }
                    }

                    return Mono.zip(processingStages, results -> {
                        List<RetrievalCandidate> combined = new ArrayList<>();
                        for (Object result : results) {
                            @SuppressWarnings("unchecked")
                            List<RetrievalCandidate> entityChunks = (List<RetrievalCandidate>) result;
                            combined.addAll(entityChunks);
                        }
                        return combined.stream().distinct().collect(Collectors.toList());
                    });
                })
                .doOnNext(candidates -> emitRanking(progressSink))
                .map(candidates -> multiSignalRanker.rank(candidates, decision, sessionId));
    }

    // ==================================================================================
    // META_DOC_SEARCH
    // ==================================================================================

    private Mono<List<RetrievalCandidate>> metaDocPipeline(String retrievalQuery, Long sessionId,
            boolean skipWholeDocument, RetrievalPlan decision, reactor.core.publisher.Sinks.Many<org.springframework.http.codec.ServerSentEvent<String>> progressSink) {
        return hybridRetrievalService.retrieve(decision.entities(), retrievalQuery, sessionId, 60, skipWholeDocument, decision.targetDocuments())
                .map(pool -> {
                    java.util.LinkedHashMap<String, List<RetrievalCandidate>> bySource = new java.util.LinkedHashMap<>();
                    for (RetrievalCandidate cand : pool) {
                        String src = cand.chunk().getMetadata().getOrDefault("sourceName", "unknown").toString();
                        bySource.computeIfAbsent(src, k -> new ArrayList<>()).add(cand);
                    }

                    List<RetrievalCandidate> docs = bySource.entrySet().stream()
                            .limit(5)
                            .flatMap(entry -> entry.getValue().stream().limit(3))
                            .distinct()
                            .collect(Collectors.toList());

                    log.info("ORCHESTRATOR: META_DOC_SEARCH → {} chunks from top {} sources: {}",
                            docs.size(),
                            Math.min(5, bySource.size()),
                            bySource.keySet().stream().limit(5).collect(Collectors.toList()));
                    return docs;
                })
                .doOnNext(candidates -> emitRanking(progressSink))
                .map(candidates -> multiSignalRanker.rank(candidates, decision, sessionId));
    }

    // ==================================================================================
    // CONCEPT_EXPANSION
    // ==================================================================================

    private Mono<List<RetrievalCandidate>> conceptExpansionPipeline(String question, String retrievalQuery,
            Long sessionId, RetrievalPlan decision, boolean skipWholeDocument, reactor.core.publisher.Sinks.Many<org.springframework.http.codec.ServerSentEvent<String>> progressSink) {

        List<String> subQueries = decision.subQueries();
        if (subQueries == null || subQueries.isEmpty()) {
            return singleSourcePipeline(question, retrievalQuery, sessionId, decision, skipWholeDocument, progressSink);
        }

        List<String> allLegs = new ArrayList<>(subQueries);
        if (!retrievalQuery.equals(question) && !allLegs.contains(retrievalQuery)) {
            allLegs.add(retrievalQuery);
            log.info("ORCHESTRATOR: CONCEPT_EXPANSION added optimizedQuery as extra retrieval leg");
        }

        List<Mono<List<RetrievalCandidate>>> retrieves = allLegs.stream()
                .map(sq -> hybridRetrievalService.retrieve(decision.entities(), sq, sessionId, 15, skipWholeDocument, decision.targetDocuments()))
                .collect(Collectors.toList());

        return Mono.zip(retrieves, results -> {
            java.util.Map<String, Double> rrfScores = new java.util.HashMap<>();
            java.util.Map<String, RetrievalCandidate> chunkMap = new java.util.HashMap<>();

            for (Object result : results) {
                @SuppressWarnings("unchecked")
                List<RetrievalCandidate> chunks = (List<RetrievalCandidate>) result;
                for (int i = 0; i < chunks.size(); i++) {
                    RetrievalCandidate chunk = chunks.get(i);
                    String contentHash = (String) chunk.chunk().getMetadata().getOrDefault("contentHash", chunk.chunk().getId());
                    chunkMap.putIfAbsent(contentHash, chunk);
                    double score = 1.0 / (60.0 + (i + 1));
                    rrfScores.merge(contentHash, score, Double::sum);
                }
            }

            List<RetrievalCandidate> top20 = rrfScores.entrySet().stream()
                    .sorted(java.util.Map.Entry.<String, Double>comparingByValue().reversed())
                    .limit(20)
                    .map(e -> {
                        RetrievalCandidate c = chunkMap.get(e.getKey());
                        return new RetrievalCandidate(c.chunk(), e.getValue());
                    })
                    .collect(Collectors.toList());

            log.info("ORCHESTRATOR: CONCEPT_EXPANSION RRF fusion — {} legs → top 20 chunks", allLegs.size());
            return top20;
        })
        .map(top20 -> rerankService.rerank(retrievalQuery, top20, 5, sessionId, RetrievalStrategy.CONCEPT_EXPANSION))
        .doOnNext(reranked -> emitRanking(progressSink))
        .map(reranked -> multiSignalRanker.rank(reranked, decision, sessionId));
    }
}
