package com.accenture.intern.docmind.aiservices.retrieval;

import com.accenture.intern.docmind.aiservices.understanding.RetrievalStrategy;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import reactor.core.publisher.Mono;
import com.accenture.intern.docmind.dto.retrieval.RetrievalCandidate;

@Slf4j
@Service
public class RerankService {

    /**
     * ms-marco-MiniLM-L-6-v2 (like most BERT-family cross-encoders) has a
     * max_position_embeddings of 512 — the ONNX graph simply doesn't accept a
     * longer sequence. Without an explicit truncation setting, the DJL tokenizer
     * does NOT truncate by default (truncation is opt-in, not automatic), so a
     * long chunk plus the query could exceed 512 tokens and either throw at
     * inference time (silently swallowed by the catch-all below, which then falls
     * back to unranked order for the whole batch) or get truncated in an
     * unpredictable way. Setting this explicitly means every (query, chunk) pair
     * is deterministically truncated to a length the model actually supports, and
     * we always know what the cross-encoder is really scoring.
     */
    private static final int MAX_SEQUENCE_LENGTH = 512;

    /**
     * Controls the balance between relevance and novelty in the MMR algorithm.
     * λ = 1.0 focuses entirely on relevance (pure score sort).
     * λ = 0.0 focuses entirely on novelty (pure diversity).
     * λ = 0.5 equally balances relevance and novelty.
     */
    private static final double MMR_LAMBDA = 0.5;

    /**
     * Note: Session boosting is now handled in the MultiSignalRanker.
     * RerankService strictly computes pure semantic similarity.
     */

    private HuggingFaceTokenizer tokenizer;
    private OrtEnvironment env;
    private OrtSession session;
    private final com.accenture.intern.docmind.config.RetrievalProperties retrievalProperties;

    public RerankService(com.accenture.intern.docmind.config.RetrievalProperties retrievalProperties) {
        this.retrievalProperties = retrievalProperties;
    }

    @Value("classpath:onnx/ms-marco-MiniLM-L-6-v2/model.onnx")
    private Resource modelResource;

    @Value("classpath:onnx/ms-marco-MiniLM-L-6-v2/tokenizer.json")
    private Resource tokenizerResource;

    @PostConstruct
    public void init() {
        try {
            log.info("Initializing ONNX Reranking Model...");

            // Extract tokenizer
            Path tokenizerPath = Files.createTempFile("tokenizer", ".json");
            try (InputStream is = tokenizerResource.getInputStream()) {
                Files.copy(is, tokenizerPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            tokenizer = HuggingFaceTokenizer.builder()
                    .optTokenizerPath(tokenizerPath)
                    .optTruncation(true)
                    .optMaxLength(MAX_SEQUENCE_LENGTH)
                    .build();

            // Extract model
            Path modelPath = Files.createTempFile("model", ".onnx");
            try (InputStream is = modelResource.getInputStream()) {
                Files.copy(is, modelPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT);
            session = env.createSession(modelPath.toString(), opts);

            log.info("Successfully loaded ONNX cross-encoder model (max_seq_len={}).", MAX_SEQUENCE_LENGTH);
        } catch (Exception e) {
            log.error("Failed to initialize RerankService. ONNX model may be missing. Run python export script.", e);
        }
    }

    @PreDestroy
    public void cleanup() {
        try {
            if (session != null)
                session.close();
            if (env != null)
                env.close();
            if (tokenizer != null)
                tokenizer.close();
        } catch (OrtException e) {
            log.error("Error closing ONNX session", e);
        }
    }

    public Mono<com.accenture.intern.docmind.dto.chat.RerankResult> rerank(String query, List<RetrievalCandidate> candidates, int topN, Long sessionId) {
        if (candidates == null || candidates.isEmpty()) {
            return Mono.just(new com.accenture.intern.docmind.dto.chat.RerankResult(Collections.emptyList(), 0, 0));
        }

        try {
            long startTime = System.currentTimeMillis();
            
            // Cap the input list before cross-encoder processing to save latency.
            // Since candidates is already sorted by RRF (Reciprocal Rank Fusion) score,
            // we discard the tail end.
            int maxCandidates = retrievalProperties.getMaxRerankCandidates();
            List<RetrievalCandidate> cappedCandidates = candidates.size() > maxCandidates 
                ? candidates.subList(0, maxCandidates) 
                : candidates;

            List<Encoding> encodings = new ArrayList<>(cappedCandidates.size());
            int maxLen = 0;
            for (RetrievalCandidate cand : cappedCandidates) {
                Encoding encoding = tokenizer.encode(query, cand.chunk().getText());
                encodings.add(encoding);
                maxLen = Math.max(maxLen, encoding.getIds().length);
            }

            int batchSize = cappedCandidates.size();
            long[][] batchInputIds = new long[batchSize][maxLen];
            long[][] batchAttentionMask = new long[batchSize][maxLen];
            long[][] batchTokenTypeIds = new long[batchSize][maxLen];

            for (int i = 0; i < batchSize; i++) {
                Encoding e = encodings.get(i);
                long[] ids = e.getIds();
                long[] mask = e.getAttentionMask();
                long[] types = e.getTypeIds();
                System.arraycopy(ids, 0, batchInputIds[i], 0, ids.length);
                System.arraycopy(mask, 0, batchAttentionMask[i], 0, mask.length);
                System.arraycopy(types, 0, batchTokenTypeIds[i], 0, types.length);
            }

            List<RetrievalCandidate> scoredCandidates = new ArrayList<>();

            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("input_ids", OnnxTensor.createTensor(env, batchInputIds));
            inputs.put("attention_mask", OnnxTensor.createTensor(env, batchAttentionMask));
            inputs.put("token_type_ids", OnnxTensor.createTensor(env, batchTokenTypeIds));

            try (OrtSession.Result results = session.run(inputs)) {
                float[][] logits = (float[][]) results.get(0).getValue();
                for (int i = 0; i < batchSize; i++) {
                    RetrievalCandidate cand = cappedCandidates.get(i);
                    float score = logits[i][0]; 
                    float sigmoidScore = (float) (1.0 / (1.0 + Math.exp(-score)));
                    RetrievalCandidate updatedCand = cand.withSemanticScore(sigmoidScore);
                    scoredCandidates.add(updatedCand);
                }
            } finally {
                for (OnnxTensor tensor : inputs.values()) {
                    tensor.close();
                }
            }

            scoredCandidates.sort((a, b) -> Double.compare(b.semanticScore(), a.semanticScore()));
            log.info("Reranked {} docs in {}ms (batched)", cappedCandidates.size(), (System.currentTimeMillis() - startTime));

            com.accenture.intern.docmind.dto.chat.RerankResult result = selectDiverse(scoredCandidates, topN);
            return Mono.just(new com.accenture.intern.docmind.dto.chat.RerankResult(result.candidates(), result.afterRerankHits(), result.latency() + (System.currentTimeMillis() - startTime)));

        } catch (Exception e) {
            log.error("Error during reranking. Falling back to original retrieval.", e);
            List<RetrievalCandidate> fallback = candidates.stream().limit(topN).collect(Collectors.toList());
            return Mono.just(new com.accenture.intern.docmind.dto.chat.RerankResult(fallback, fallback.size(), 0));
        }
    }

    /**
     * Implements Maximum Marginal Relevance (MMR) for post-reranking diversity.
     * <p>
     * Instead of rigid quotas per source document, MMR dynamically balances the
     * cross-encoder relevance score with a novelty penalty. If a chunk is
     * semantically
     * (lexically) identical to one already selected, its MMR score plummets,
     * allowing
     * novel information from other chunks (or other documents) to surface
     * naturally.
     */
    private com.accenture.intern.docmind.dto.chat.RerankResult selectDiverse(List<RetrievalCandidate> scoredDocs, int topN) {
        long t0 = System.currentTimeMillis();
        if (scoredDocs.isEmpty()) {
            long latency = System.currentTimeMillis() - t0;
            return new com.accenture.intern.docmind.dto.chat.RerankResult(Collections.emptyList(), 0, latency);
        }

        List<RetrievalCandidate> selected = new ArrayList<>();
        selected.add(scoredDocs.get(0));

        while (selected.size() < topN && selected.size() < scoredDocs.size()) {
            double bestMmrScore = -Double.MAX_VALUE;
            RetrievalCandidate bestCandidate = null;

            for (RetrievalCandidate cand : scoredDocs) {
                if (selected.contains(cand)) {
                    continue;
                }

                double maxSimilarityToSelected = 0.0;
                Set<String> candidateWords = wordSet(cand.chunk().getText());

                for (RetrievalCandidate sel : selected) {
                    double sim = jaccardSimilarity(candidateWords, wordSet(sel.chunk().getText()));
                    if (sim > maxSimilarityToSelected) {
                        maxSimilarityToSelected = sim;
                    }
                }

                double mmrScore = (MMR_LAMBDA * cand.semanticScore()) - ((1.0 - MMR_LAMBDA) * maxSimilarityToSelected);
                if (mmrScore > bestMmrScore) {
                    bestMmrScore = mmrScore;
                    bestCandidate = cand;
                }
            }

            if (bestCandidate != null) {
                selected.add(bestCandidate);
            }
        }

        long latency = System.currentTimeMillis() - t0;
        return new com.accenture.intern.docmind.dto.chat.RerankResult(selected, selected.size(), latency);
    }

    private Set<String> wordSet(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(text.toLowerCase().split("\\W+"))
                .filter(w -> w.length() > 2) // skip tiny/stop-ish tokens, they dominate overlap counts
                .collect(Collectors.toSet());
    }

    private double jaccardSimilarity(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) intersection.size() / union.size();
    }


}


