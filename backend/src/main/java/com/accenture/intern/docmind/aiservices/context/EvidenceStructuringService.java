package com.accenture.intern.docmind.aiservices.context;

import com.accenture.intern.docmind.aiservices.understanding.MergeOperation;
import com.accenture.intern.docmind.dto.chat.AggregatedEvidence;
import com.accenture.intern.docmind.dto.chat.VisualEvidence;
import com.accenture.intern.docmind.dto.retrieval.RetrievalCandidate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class EvidenceStructuringService {

    private static final Pattern YEAR_PATTERN = Pattern.compile("\\b(19\\d{2}|20\\d{2}|[1-9]\\d* (million|billion|crore|lakh) years|10\\^[-]?\\d+ sec)\\b", Pattern.CASE_INSENSITIVE);

    public AggregatedEvidence structure(List<RetrievalCandidate> textEvidence, List<VisualEvidence> visualEvidence, MergeOperation mergeOperation) {
        StringBuilder structuredOutput = new StringBuilder();
        int initialSize = textEvidence == null ? 0 : textEvidence.size();

        // 1. Process Visual Evidence (Keep existing visual logic)
        List<VisualEvidence> updatedVisuals = new ArrayList<>();
        if (visualEvidence != null && !visualEvidence.isEmpty()) {
            structuredOutput.append("=====================\n");
            structuredOutput.append("Visual Evidence\n\n");
            int imgCounter = 1;
            for (VisualEvidence ve : visualEvidence) {
                String stableId = String.format("IMG_%02d", imgCounter++);
                String displayTitle = "Image from " + ve.sourceDocument();
                String captionText = ve.caption() != null ? ve.caption() : "";
                Matcher m = Pattern.compile("(?s)\\[Summary: (.*?)\\]").matcher(captionText);
                Matcher wikiM = Pattern.compile("(?i)Image Caption: (.*?)(?:\\n|$)").matcher(captionText);
                
                String summary = null;
                if (m.find()) {
                    summary = m.group(1).trim();
                } else if (wikiM.find()) {
                    summary = wikiM.group(1).trim();
                }
                
                if (summary != null) {
                    String[] parts = summary.split("\\.");
                    String title = parts[0];
                    title = title.replaceAll("^(?i)(A|The|This) (detailed |descriptive )?(diagram|image|chart|screenshot|photo) (showing|of|illustrating|comparing) ", "");
                    if (title.length() > 60) title = title.substring(0, 60) + "...";
                    if (!title.isEmpty()) displayTitle = title.substring(0, 1).toUpperCase() + title.substring(1);
                }

                VisualEvidence updated = new VisualEvidence(
                        ve.semanticId(), stableId, ve.imageUrl(), ve.thumbnailUrl(),
                        displayTitle, ve.score(), ve.sourceDocument(),
                        ve.sectionPath(), ve.page(), ve.heading(), ve.retrievalReason()
                );
                updatedVisuals.add(updated);

                structuredOutput.append(String.format("Image %d\nDisplay Title: %s\nDetails: %s\n\n",
                        imgCounter - 1, displayTitle, ve.caption()));
            }
        }

        if (textEvidence == null || textEvidence.isEmpty()) {
            if (updatedVisuals.isEmpty()) {
                structuredOutput.append("No relevant evidence found.");
            }
            return new AggregatedEvidence(structuredOutput.toString().trim(), updatedVisuals, Collections.emptyList());
        }

        // 2. Remove Duplicates (by exact chunk ID or text)
        List<RetrievalCandidate> deduplicated = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        for (RetrievalCandidate cand : textEvidence) {
            String id = cand.chunk().getId();
            if (id != null && seenIds.add(id)) {
                deduplicated.add(cand);
            } else if (id == null) {
                deduplicated.add(cand); // if somehow no ID, keep it
            }
        }
        int duplicatesRemoved = initialSize - deduplicated.size();

        // 3. Group by Document (sourceName) and SourceType
        Map<String, List<RetrievalCandidate>> grouped = new HashMap<>();
        for (RetrievalCandidate cand : deduplicated) {
            String sourceName = (String) cand.chunk().getMetadata().getOrDefault("sourceName", "unknown");
            grouped.computeIfAbsent(sourceName, k -> new ArrayList<>()).add(cand);
        }

        List<RetrievalCandidate> merged = new ArrayList<>();
        int mergedCount = 0;

        for (Map.Entry<String, List<RetrievalCandidate>> entry : grouped.entrySet()) {
            List<RetrievalCandidate> docChunks = entry.getValue();
            if (docChunks.isEmpty()) continue;
            
            String sourceType = (String) docChunks.get(0).chunk().getMetadata().getOrDefault("sourceType", "UNKNOWN");
            
            switch (sourceType.toUpperCase()) {
                case "PDF": {
                    // Sort PDF chunks
                    docChunks.sort(Comparator
                            .comparingInt((RetrievalCandidate c) -> getAsInt(c.chunk().getMetadata(), "page", 0))
                            .thenComparingInt((RetrievalCandidate c) -> getAsInt(c.chunk().getMetadata(), "charStart", 0)));
                    
                    // Merge PDF chunks
                    RetrievalCandidate current = docChunks.get(0);
                    for (int i = 1; i < docChunks.size(); i++) {
                        RetrievalCandidate next = docChunks.get(i);
                        
                        int currPage = getAsInt(current.chunk().getMetadata(), "page", -1);
                        int nextPage = getAsInt(next.chunk().getMetadata(), "page", -1);
                        
                        int currEnd = getAsInt(current.chunk().getMetadata(), "charEnd", -1);
                        int nextStart = getAsInt(next.chunk().getMetadata(), "charStart", -1);
                        
                        boolean adjacentPage = Math.abs(currPage - nextPage) <= 1 && currPage != -1;
                        boolean overlappingOrAdjacentText = currEnd != -1 && nextStart != -1 && (nextStart <= currEnd + 50);
                        boolean similarScore = Math.abs(current.finalScore() - next.finalScore()) <= 0.15;
                        boolean withinLengthLimit = (current.chunk().getText().length() + next.chunk().getText().length()) <= 2500;
                        
                        if (adjacentPage && overlappingOrAdjacentText && similarScore && withinLengthLimit) {
                            String newText = current.chunk().getText() + "\n... " + next.chunk().getText();
                            current.chunk().getMetadata().put("charEnd", Math.max(currEnd, getAsInt(next.chunk().getMetadata(), "charEnd", currEnd)));
                            
                            // Merge bounding boxes
                            try {
                                String bboxes1 = (String) current.chunk().getMetadata().get("boundingBoxes");
                                String bboxes2 = (String) next.chunk().getMetadata().get("boundingBoxes");
                                if (bboxes1 != null && bboxes2 != null) {
                                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                                    com.fasterxml.jackson.databind.JsonNode arr1 = mapper.readTree(bboxes1);
                                    com.fasterxml.jackson.databind.JsonNode arr2 = mapper.readTree(bboxes2);
                                    if (arr1.isArray() && arr2.isArray()) {
                                        com.fasterxml.jackson.databind.node.ArrayNode mergedArr = mapper.createArrayNode();
                                        mergedArr.addAll((com.fasterxml.jackson.databind.node.ArrayNode) arr1);
                                        mergedArr.addAll((com.fasterxml.jackson.databind.node.ArrayNode) arr2);
                                        current.chunk().getMetadata().put("boundingBoxes", mapper.writeValueAsString(mergedArr));
                                    }
                                } else if (bboxes2 != null) {
                                    current.chunk().getMetadata().put("boundingBoxes", bboxes2);
                                }
                            } catch (Exception e) {}
                            
                            double maxScore = Math.max(current.finalScore(), next.finalScore());
                            current = new RetrievalCandidate(new org.springframework.ai.document.Document(current.chunk().getId(), newText, current.chunk().getMetadata()), maxScore);
                            mergedCount++;
                        } else {
                            merged.add(current);
                            current = next;
                        }
                    }
                    merged.add(current);
                    break;
                }
                case "WIKIPEDIA":
                case "TEXT":
                case "MARKDOWN":
                case "HTML": {
                    // Sort Semantic/Text chunks
                    docChunks.sort(Comparator.comparingInt((RetrievalCandidate c) -> getAsInt(c.chunk().getMetadata(), "chunkIndex", 0)));
                    
                    // Merge Semantic chunks
                    RetrievalCandidate current = docChunks.get(0);
                    for (int i = 1; i < docChunks.size(); i++) {
                        RetrievalCandidate next = docChunks.get(i);
                        
                        String currSection = (String) current.chunk().getMetadata().getOrDefault("sectionPath", "");
                        String nextSection = (String) next.chunk().getMetadata().getOrDefault("sectionPath", "");
                        
                        int currIdx = getAsInt(current.chunk().getMetadata(), "chunkIndex", -2);
                        int nextIdx = getAsInt(next.chunk().getMetadata(), "chunkIndex", -1);
                        
                        boolean sameSection = currSection.equals(nextSection);
                        boolean adjacentIdx = (nextIdx - currIdx) == 1;
                        boolean similarScore = Math.abs(current.finalScore() - next.finalScore()) <= 0.15;
                        boolean withinLengthLimit = (current.chunk().getText().length() + next.chunk().getText().length()) <= 2500;
                        
                        if (sameSection && adjacentIdx && similarScore && withinLengthLimit) {
                            String newText = current.chunk().getText() + "\n\n" + next.chunk().getText();
                            current.chunk().getMetadata().put("chunkIndex", nextIdx); // Update index to represent end of merge
                            
                            double maxScore = Math.max(current.finalScore(), next.finalScore());
                            current = new RetrievalCandidate(new org.springframework.ai.document.Document(current.chunk().getId(), newText, current.chunk().getMetadata()), maxScore);
                            mergedCount++;
                        } else {
                            merged.add(current);
                            current = next;
                        }
                    }
                    merged.add(current);
                    break;
                }
                case "IMAGE":
                case "PDF_IMAGE":
                default: {
                    // No merging for images or unknown
                    merged.addAll(docChunks);
                    break;
                }
            }
        }

        // 5. Final Sort: Primary: Score, Secondary: original internal chunking offsets/pages
        merged.sort(Comparator
                .comparing(RetrievalCandidate::finalScore).reversed()
                .thenComparingInt((RetrievalCandidate c) -> getAsInt(c.chunk().getMetadata(), "page", 0))
                .thenComparingInt((RetrievalCandidate c) -> getAsInt(c.chunk().getMetadata(), "charStart", 0))
                .thenComparingInt((RetrievalCandidate c) -> getAsInt(c.chunk().getMetadata(), "chunkIndex", 0)));


        // 6. Split into Primary (>0.85) and Supporting (<0.85)
        List<RetrievalCandidate> primary = new ArrayList<>();
        List<RetrievalCandidate> supporting = new ArrayList<>();
        for (RetrievalCandidate c : merged) {
            if (c.finalScore() > 0.85) {
                primary.add(c);
            } else {
                supporting.add(c);
            }
        }

        structuredOutput.append("Question Context\n\n");
        
        if (!primary.isEmpty()) {
            structuredOutput.append("=====================\n");
            structuredOutput.append("Primary Evidence\n\n");
            int idx = 1;
            for (RetrievalCandidate c : primary) {
                appendCandidate(structuredOutput, c, idx++);
            }
        }
        
        if (!supporting.isEmpty()) {
            structuredOutput.append("=====================\n");
            structuredOutput.append("Supporting Evidence\n\n");
            int idx = primary.size() + 1;
            for (RetrievalCandidate c : supporting) {
                appendCandidate(structuredOutput, c, idx++);
            }
        }

        // 7. Hybrid Timeline Extraction
        List<String> timelineEvents = new ArrayList<>();
        for (RetrievalCandidate c : merged) {
            String text = c.chunk().getText();
            Matcher m = YEAR_PATTERN.matcher(text);
            if (m.find()) {
                // opportunistic timeline
                String snippet = text.length() > 100 ? text.substring(0, 100) + "..." : text;
                timelineEvents.add(m.group(1) + " : " + snippet.replaceAll("\\s+", " "));
            }
        }
        
        if (!timelineEvents.isEmpty()) {
            structuredOutput.append("=====================\n");
            structuredOutput.append("Timeline (opportunistic)\n\n");
            for (String event : timelineEvents) {
                structuredOutput.append("- ").append(event).append("\n");
            }
            structuredOutput.append("\n");
        }

        // 8. Notes
        structuredOutput.append("=====================\n");
        structuredOutput.append("Notes\n\n");
        structuredOutput.append(String.format("Merged: %d chunks\n", mergedCount));
        structuredOutput.append(String.format("Duplicates removed: %d\n", duplicatesRemoved));

        List<RetrievalCandidate> orderedCandidates = new ArrayList<>();
        orderedCandidates.addAll(primary);
        orderedCandidates.addAll(supporting);

        return new AggregatedEvidence(structuredOutput.toString().trim(), updatedVisuals, orderedCandidates);
    }

    private void appendCandidate(StringBuilder sb, RetrievalCandidate cand, int index) {
        String name = (String) cand.chunk().getMetadata().getOrDefault("sourceName", "unknown");
        Integer page = getAsInt(cand.chunk().getMetadata(), "page", null);
        String pageStr = page != null ? "Page " + page : "Unknown Page";
        double score = cand.finalScore();
        
        sb.append(String.format("[%d]\n", index));
        sb.append(String.format("Source: %s\n", name));
        sb.append(String.format("%s\n", pageStr));
        sb.append(String.format("Score: %.2f\n\n", score));
        sb.append(cand.chunk().getText()).append("\n\n");
    }

    private Integer getAsInt(Map<String, Object> metadata, String key, Integer defaultValue) {
        Object val = metadata.get(key);
        if (val == null) return defaultValue;
        if (val instanceof Integer) return (Integer) val;
        if (val instanceof Number) return ((Number) val).intValue();
        if (val instanceof String) {
            try {
                return Integer.parseInt((String) val);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }
}
