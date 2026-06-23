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
            tokenizer = HuggingFaceTokenizer.newInstance(tokenizerPath);

            // Extract model
            Path modelPath = Files.createTempFile("model", ".onnx");
            try (InputStream is = modelResource.getInputStream()) {
                Files.copy(is, modelPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT);
            session = env.createSession(modelPath.toString(), opts);

            log.info("Successfully loaded ONNX cross-encoder model.");
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
            List<DocumentScore> scoredDocs = new ArrayList<>();

            for (Document doc : documents) {
                // Tokenize query and document text as a pair
                Encoding encoding = tokenizer.encode(query, doc.getText());
                
                long[] inputIds = encoding.getIds();
                long[] attentionMask = encoding.getAttentionMask();
                long[] tokenTypeIds = encoding.getTypeIds();

                // Create 2D arrays expected by ONNX model [batch_size, seq_len]
                long[][] batchInputIds = new long[][] { inputIds };
                long[][] batchAttentionMask = new long[][] { attentionMask };
                long[][] batchTokenTypeIds = new long[][] { tokenTypeIds };

                Map<String, OnnxTensor> inputs = new HashMap<>();
                inputs.put("input_ids", OnnxTensor.createTensor(env, batchInputIds));
                inputs.put("attention_mask", OnnxTensor.createTensor(env, batchAttentionMask));
                inputs.put("token_type_ids", OnnxTensor.createTensor(env, batchTokenTypeIds));

                try (OrtSession.Result results = session.run(inputs)) {
                    float[][] logits = (float[][]) results.get(0).getValue();
                    float score = logits[0][0]; // cross-encoder outputs a single logit per pair
                    
                    // Sigmoid activation (optional, but good for [0, 1] range scaling)
                    float sigmoidScore = (float) (1.0 / (1.0 + Math.exp(-score)));
                    
                    // Update metadata with rerank score so frontend can display it
                    Map<String, Object> metadata = new HashMap<>(doc.getMetadata());
                    metadata.put("score", sigmoidScore);
                    
                    Object pineconeScore = doc.getMetadata().get("score");
                    if (pineconeScore != null) {
                        metadata.put("pinecone_score", pineconeScore); // keep old one just in case
                    }
                    
                    Document newDoc = new Document(doc.getId(), doc.getText(), metadata);
                    scoredDocs.add(new DocumentScore(newDoc, sigmoidScore));
                }
                
                // Cleanup tensors
                for (OnnxTensor tensor : inputs.values()) {
                    tensor.close();
                }
            }

            // Sort descending by score
            scoredDocs.sort((a, b) -> Float.compare(b.score, a.score));

            log.info("Reranked {} docs in {}ms", documents.size(), (System.currentTimeMillis() - startTime));
            for (DocumentScore ds : scoredDocs) {
                log.info("Score={} Chunk={} Source={}", 
                         ds.score, 
                         ds.doc.getMetadata().get("chunkIndex"),
                         ds.doc.getMetadata().get("sourceName"));
            }

            return scoredDocs.stream()
                    .limit(topN)
                    .map(ds -> ds.doc)
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("Error during reranking. Falling back to original retrieval.", e);
            return documents.stream().limit(topN).collect(Collectors.toList());
        }
    }

    private record DocumentScore(Document doc, float score) {}
}
