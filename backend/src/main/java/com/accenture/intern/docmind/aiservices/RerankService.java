package com.accenture.intern.docmind.aiservices;

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
     * Two chunks with word-overlap (Jaccard similarity) above this threshold are
     * treated as near-duplicates during final selection — typically adjacent
     * overlapping chunk windows from the same document, or the same paragraph
     * surfaced via both the dense and keyword retrieval paths. Without this, the
     * top-N could end up being 5 near-identical slices of the same paragraph,
     * which wastes the LLM's limited context on redundant text instead of giving
     * it broader coverage of the source material. 0.8 is deliberately high — it
     * only filters genuine near-duplicates, not merely related chunks.
     */
    private static final double DIVERSITY_SIMILARITY_THRESHOLD = 0.8;

    private HuggingFaceTokenizer tokenizer;
    private OrtEnvironment env;
    private OrtSession session;

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
            if (session != null) session.close();
            if (env != null) env.close();
            if (tokenizer != null) tokenizer.close();
        } catch (OrtException e) {
            log.error("Error closing ONNX session", e);
        }
    }

    public List<Document> rerank(String query, List<Document> documents, int topN) {
        if (session == null || documents.isEmpty()) {
            return documents.stream().limit(topN).collect(Collectors.toList());
        }

        try {
            long startTime = System.currentTimeMillis();

            // Tokenize every (query, chunk) pair up front, then pad to a common length
            // so the whole batch can go through the ONNX graph in a single session.run()
            // call instead of one run() per document. Cross-encoders are far more
            // efficient batched - this turns N sequential inferences (each paying its
            // own tensor-allocation + graph-execution overhead) into one inference over
            // an [N, maxLen] tensor, which is the main thing making rerank slow today.
            List<Encoding> encodings = new ArrayList<>(documents.size());
            int maxLen = 0;
            for (Document doc : documents) {
                Encoding encoding = tokenizer.encode(query, doc.getText());
                encodings.add(encoding);
                maxLen = Math.max(maxLen, encoding.getIds().length);
            }

            int batchSize = documents.size();
            long[][] batchInputIds = new long[batchSize][maxLen];
            long[][] batchAttentionMask = new long[batchSize][maxLen];
            long[][] batchTokenTypeIds = new long[batchSize][maxLen];

            for (int i = 0; i < batchSize; i++) {
                Encoding e = encodings.get(i);
                long[] ids = e.getIds();
                long[] mask = e.getAttentionMask();
                long[] types = e.getTypeIds();
                // Right-pad with 0s (attention_mask=0 tells the model to ignore the
                // padding, which is the standard BERT-family padding convention).
                System.arraycopy(ids, 0, batchInputIds[i], 0, ids.length);
                System.arraycopy(mask, 0, batchAttentionMask[i], 0, mask.length);
                System.arraycopy(types, 0, batchTokenTypeIds[i], 0, types.length);
            }

            List<DocumentScore> scoredDocs = new ArrayList<>();

            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("input_ids", OnnxTensor.createTensor(env, batchInputIds));
            inputs.put("attention_mask", OnnxTensor.createTensor(env, batchAttentionMask));
            inputs.put("token_type_ids", OnnxTensor.createTensor(env, batchTokenTypeIds));

            try (OrtSession.Result results = session.run(inputs)) {
                float[][] logits = (float[][]) results.get(0).getValue();
                for (int i = 0; i < batchSize; i++) {
                    Document doc = documents.get(i);
                    float score = logits[i][0]; // cross-encoder outputs a single logit per pair
                    float sigmoidScore = (float) (1.0 / (1.0 + Math.exp(-score)));

                    Map<String, Object> metadata = new HashMap<>(doc.getMetadata());
                    metadata.put("score", sigmoidScore);

                    Object pineconeScore = doc.getMetadata().get("score");
                    if (pineconeScore != null) {
                        metadata.put("pinecone_score", pineconeScore); // keep old one just in case
                    }

                    Document newDoc = new Document(doc.getId(), doc.getText(), metadata);
                    scoredDocs.add(new DocumentScore(newDoc, sigmoidScore));
                }
            } finally {
                for (OnnxTensor tensor : inputs.values()) {
                    tensor.close();
                }
            }

            // Sort descending by score
            scoredDocs.sort((a, b) -> Float.compare(b.score, a.score));

            log.info("Reranked {} docs in {}ms (batched)", documents.size(), (System.currentTimeMillis() - startTime));
            for (DocumentScore ds : scoredDocs) {
                log.info("Score={} Chunk={} Source={}", 
                         ds.score, 
                         ds.doc.getMetadata().get("chunkIndex"),
                         ds.doc.getMetadata().get("sourceName"));
            }

            List<Document> finalSelection = selectDiverse(scoredDocs, topN);

            List<Object> finalSources = finalSelection.stream()
                    .map(d -> d.getMetadata().getOrDefault("sourceName", "unknown"))
                    .collect(Collectors.toList());
            log.info("Final selection ({} chunks) sources: {}", finalSelection.size(), finalSources);

            return finalSelection;

        } catch (Exception e) {
            log.error("Error during reranking. Falling back to original retrieval.", e);
            return documents.stream().limit(topN).collect(Collectors.toList());
        }
    }

    /**
     * Greedily walks the score-sorted candidates and takes the first topN that
     * aren't near-duplicates of something already picked. This keeps the highest
     * scoring chunk of any near-duplicate cluster (since the list is already
     * sorted descending) and skips the rest, so the final context sent to the LLM
     * covers more of the source material instead of repeating the same passage.
     * <p>
     * Once duplicates are filtered, selection is balanced across distinct source
     * documents (round-robin by {@code sourceName}) rather than a pure global
     * top-N by score. Without this, a query like "compare resume A and resume B"
     * can retrieve chunks from both documents into the candidate pool, but the
     * cross-encoder scores every chunk against the literal comparison wording
     * independently — it has no notion of "represent every document mentioned."
     * If one document's chunks score even slightly higher across the board (e.g.
     * more keyword overlap with the query's phrasing), a pure top-N cut can starve
     * the other document out of the final context entirely, even though it was
     * retrieved into the candidate pool. Round-robin guarantees every source that
     * made it into the pool gets a fair shot at making the final cut, while still
     * respecting score order within each source.
     */
    private List<Document> selectDiverse(List<DocumentScore> scoredDocs, int topN) {
        List<Document> deduped = new ArrayList<>();
        List<Set<String>> selectedWordSets = new ArrayList<>();
        Set<String> selectedIds = new HashSet<>();

        for (DocumentScore ds : scoredDocs) {
            Set<String> words = wordSet(ds.doc.getText());
            boolean isDuplicate = false;
            for (Set<String> existing : selectedWordSets) {
                if (jaccardSimilarity(words, existing) >= DIVERSITY_SIMILARITY_THRESHOLD) {
                    isDuplicate = true;
                    break;
                }
            }

            if (!isDuplicate) {
                deduped.add(ds.doc);
                selectedWordSets.add(words);
                selectedIds.add(ds.doc.getId());
            }
        }

        // If dedup filtering left us short (e.g. nearly everything was a
        // near-duplicate of everything else, which only happens on very small or
        // very repetitive corpora), backfill with the next-highest-scoring docs
        // regardless of similarity so we still have enough to round-robin over.
        if (deduped.size() < topN) {
            for (DocumentScore ds : scoredDocs) {
                if (deduped.size() >= topN) {
                    break;
                }
                if (!selectedIds.contains(ds.doc.getId())) {
                    deduped.add(ds.doc);
                    selectedIds.add(ds.doc.getId());
                }
            }
        }

        return roundRobinBySource(deduped, topN);
    }

    /**
     * Groups score-ordered, deduplicated documents by sourceName (preserving each
     * group's internal score order) and takes them in round-robin turns —
     * highest-scoring unpicked chunk from each source, one source at a time —
     * until topN is reached. With a single source this is identical to a plain
     * top-N cut; with multiple sources it guarantees none gets shut out just
     * because another source's chunks scored a bit higher overall.
     */
    private List<Document> roundRobinBySource(List<Document> scoreOrderedDocs, int topN) {
        if (scoreOrderedDocs.size() <= topN) {
            return scoreOrderedDocs;
        }

        Map<Object, List<Document>> bySource = new LinkedHashMap<>();
        for (Document doc : scoreOrderedDocs) {
            Object source = doc.getMetadata().getOrDefault("sourceName", "unknown");
            bySource.computeIfAbsent(source, k -> new ArrayList<>()).add(doc);
        }

        // Single source in the pool: round-robin degenerates to a no-op, so skip
        // straight to the plain top-N cut rather than doing extra work for nothing.
        if (bySource.size() <= 1) {
            return scoreOrderedDocs.subList(0, topN);
        }

        List<Document> result = new ArrayList<>();
        Map<Object, Integer> nextIndex = new HashMap<>();
        List<Object> sourceOrder = new ArrayList<>(bySource.keySet());

        outer:
        while (result.size() < topN) {
            boolean madeProgress = false;
            for (Object source : sourceOrder) {
                if (result.size() >= topN) {
                    break outer;
                }
                List<Document> docsForSource = bySource.get(source);
                int idx = nextIndex.getOrDefault(source, 0);
                if (idx < docsForSource.size()) {
                    result.add(docsForSource.get(idx));
                    nextIndex.put(source, idx + 1);
                    madeProgress = true;
                }
            }
            if (!madeProgress) {
                break; // every source's chunks exhausted before reaching topN
            }
        }

        return result;
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

    private record DocumentScore(Document doc, float score) {}
}
