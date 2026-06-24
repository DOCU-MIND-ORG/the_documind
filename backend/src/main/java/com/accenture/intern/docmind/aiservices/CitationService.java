package com.accenture.intern.docmind.aiservices;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class CitationService {

    /**
     * Matches [CITE:n] tags the LLM writes inline in its response, per the
     * "id" attribute assigned in ContextBuilderService.buildContextBlock's
     * <CITATION id="n"> markers (1-indexed, in the same order as the
     * documents list passed to extractCitations). Used to figure out which
     * of the *retrieved* documents were actually *cited* in the final
     * answer, so the UI only shows citation cards for chunks the model
     * really used - not every chunk that was merely retrieved and shown to
     * it as candidate context.
     * <p>
     * The rag prompt's own examples only ever show separate adjacent tags
     * ([CITE:1][CITE:3]), but Gemini doesn't reliably stick to that and
     * sometimes writes a single tag with a comma-separated id list instead
     * ([CITE:1, 4], [CITE:27, 28]) - a more "natural list" phrasing the
     * prompt never asked for but didn't explicitly rule out either. A
     * pattern that only matched a single \d+ would silently fail on that
     * variant: the whole bracket would be left as literal, unparsed text in
     * the response (exactly what showed up on screen) AND those ids would
     * never make it into citationsJson, with no error or log to explain why -
     * worse than an out-of-range id, since that case at least logs a
     * warning. Matching one-or-more comma/whitespace-separated digit groups
     * inside a single tag, in addition to separate tags, covers both shapes
     * the model is actually observed to produce.
     */
    private static final Pattern CITE_TAG_PATTERN = Pattern.compile("\\[CITE:\\s*([\\d,\\s]+?)\\s*]");

    public List<Map<String, Object>> extractCitations(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }

        return documents.stream()
                .map(this::toCitationMap)
                .collect(Collectors.toList());
    }

    /**
     * Same as {@link #extractCitations(List)}, but first narrows documents
     * down to only those whose 1-indexed position was actually referenced via
     * a [CITE:n] tag somewhere in responseText. This is what should be used
     * once the full response is available (i.e. after streaming completes) -
     * extractCitations alone, called before generation, has no way to know
     * which of the retrieved chunks the model will end up using versus
     * silently ignoring.
     * <p>
     * If responseText contains no [CITE:n] tags at all (e.g. the model
     * answered without citing, or this is a non-RAG general response),
     * returns an empty list rather than falling back to "all documents" -
     * citation cards should only ever represent chunks the answer is
     * grounded in, not chunks that were merely retrieved.
     */
    public List<Map<String, Object>> extractCitedCitations(List<Document> documents, String responseText) {
        if (documents == null || documents.isEmpty() || responseText == null) {
            return List.of();
        }

        Set<Integer> citedIds = new LinkedHashSet<>();
        Matcher matcher = CITE_TAG_PATTERN.matcher(responseText);
        while (matcher.find()) {
            // group(1) is everything between "[CITE:" and "]" - usually one
            // number, but can be a comma/whitespace-separated list like
            // "1, 4" when the model writes [CITE:1, 4] instead of separate
            // [CITE:1][CITE:4] tags. Splitting unconditionally handles both:
            // a single id splits into a one-element array.
            for (String idStr : matcher.group(1).split("[,\\s]+")) {
                if (idStr.isBlank()) continue;
                try {
                    citedIds.add(Integer.parseInt(idStr.trim()));
                } catch (NumberFormatException ignored) {
                    // Malformed id within the tag (shouldn't happen given the
                    // [\d,\s]+ pattern, but never let a parse hiccup break
                    // citation rendering for the rest of the response).
                }
            }
        }

        if (citedIds.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (Integer id : citedIds) {
            int index = id - 1; // [CITE:1] -> documents.get(0), matching buildContextBlock's 1-indexed <CITATION id="n">
            if (index >= 0 && index < documents.size()) {
                Map<String, Object> citation = toCitationMap(documents.get(index));
                // The id the model actually wrote as [CITE:n] in the response text -
                // without this, the frontend has no way to tell which card a given
                // inline [CITE:7] marker is supposed to point to. It can only show
                // "here are the sources used" as an unordered group, not link each
                // inline number to its specific card, which is exactly the "cite 7
                // inline but no reference to anything" bug this fixes.
                citation.put("citeId", id);
                result.add(citation);
            } else {
                // The model wrote [CITE:n] for an id that was never offered to it
                // (n > documents.size(), or n <= 0) - i.e. it hallucinated a
                // citation number rather than referencing real context. Logged at
                // WARN specifically so this is distinguishable in server logs from
                // a real pipeline bug (e.g. documents list being truncated/
                // reordered between prompt-building and citation extraction,
                // which would instead show valid ids resolving to the WRONG
                // chunk, not an out-of-range id like this).
                log.warn("Model cited [CITE:{}] but only {} document(s) were in context - likely a hallucinated citation number, dropping it", id, documents.size());
            }
        }
        return result;
    }

    private Map<String, Object> toCitationMap(Document doc) {
        // Extract score from metadata
        Object scoreObj = doc.getMetadata().get("score");
        Double score = null;

        if (scoreObj instanceof Number) {
            score = ((Number) scoreObj).doubleValue();
        } else if (scoreObj instanceof String) {
            try {
                score = Double.parseDouble((String) scoreObj);
            } catch (NumberFormatException e) {
                // ignore
            }
        }

        Object imageUrlObj = doc.getMetadata().get("imageUrl");
        String imageUrl = imageUrlObj instanceof String s && !s.isBlank() ? s : null;
        boolean isImage = imageUrl != null;

        Map<String, Object> citation = new java.util.LinkedHashMap<>();
        citation.put("sourceName", doc.getMetadata().getOrDefault("sourceName", "unknown"));
        citation.put("sourceType", doc.getMetadata().getOrDefault("sourceType", ""));
        citation.put("chunkIndex", doc.getMetadata().getOrDefault("chunkIndex", 0));
        citation.put("excerpt", doc.getText().substring(0, Math.min(200, doc.getText().length())) + "…");
        citation.put("fullExcerpt", doc.getText());
        citation.put("score", score != null ? score : 0.0d);
        citation.put("documentId", doc.getMetadata().getOrDefault("documentId", ""));
        citation.put("isImage", isImage);
        citation.put("imageUrl", imageUrl);
        return citation;
    }
}

