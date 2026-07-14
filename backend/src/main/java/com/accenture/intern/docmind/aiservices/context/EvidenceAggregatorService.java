package com.accenture.intern.docmind.aiservices.context;

import com.accenture.intern.docmind.aiservices.understanding.MergeOperation;
import com.accenture.intern.docmind.dto.chat.AggregatedEvidence;
import com.accenture.intern.docmind.dto.chat.VisualEvidence;
import com.accenture.intern.docmind.dto.retrieval.RetrievalCandidate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class EvidenceAggregatorService {

    public AggregatedEvidence aggregate(List<RetrievalCandidate> textEvidence, List<VisualEvidence> visualEvidence, MergeOperation mergeOperation) {
        StringBuilder aggregatedString = new StringBuilder();

        // 1. Process Visual Evidence and assign stable IDs
        List<VisualEvidence> updatedVisuals = new ArrayList<>();
        if (visualEvidence != null && !visualEvidence.isEmpty()) {
            aggregatedString.append("=== Visual Evidence ===\n");
            int imgCounter = 1;
            for (VisualEvidence ve : visualEvidence) {
                String stableId = String.format("IMG_%02d", imgCounter++);
                String displayTitle = "Image from " + ve.sourceDocument();
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\[Summary: (.*?)\\]").matcher(ve.caption() != null ? ve.caption() : "");
                if (m.find()) {
                    String summary = m.group(1).trim();
                    String[] parts = summary.split("\\.");
                    String title = parts[0];
                    title = title.replaceAll("^(?i)(A|The|This) (detailed |descriptive )?(diagram|image|chart|screenshot|photo) (showing|of|illustrating|comparing) ", "");
                    if (title.length() > 60) {
                        title = title.substring(0, 60) + "...";
                    }
                    if (!title.isEmpty()) {
                        displayTitle = title.substring(0, 1).toUpperCase() + title.substring(1);
                    }
                }

                VisualEvidence updated = new VisualEvidence(
                        ve.semanticId(),
                        stableId,
                        ve.imageUrl(),
                        ve.thumbnailUrl(),
                        displayTitle,
                        ve.score(),
                        ve.sourceDocument()
                );
                updatedVisuals.add(updated);

                aggregatedString.append(String.format("Image %d (shown above)\nDisplay Title: %s\nDetails: %s\n\n",
                        imgCounter - 1, displayTitle, ve.caption()));
            }
        }

        // 2. Process Text Evidence
        List<RetrievalCandidate> orderedCandidates = new ArrayList<>();
        if (textEvidence != null && !textEvidence.isEmpty()) {
            aggregatedString.append("=== Text Evidence ===\n");
            
            if (mergeOperation == MergeOperation.COMPARE) {
                java.util.Map<String, List<RetrievalCandidate>> grouped = new java.util.LinkedHashMap<>();
                for (RetrievalCandidate cand : textEvidence) {
                    String purpose = (String) cand.chunk().getMetadata().getOrDefault("purpose", "General Retrieval");
                    grouped.computeIfAbsent(purpose, k -> new ArrayList<>()).add(cand);
                }
                
                int index = 1;
                for (java.util.Map.Entry<String, List<RetrievalCandidate>> entry : grouped.entrySet()) {
                    aggregatedString.append("--- Evidence for: ").append(entry.getKey()).append(" ---\n");
                    for (RetrievalCandidate cand : entry.getValue()) {
                        orderedCandidates.add(cand);
                        String name = (String) cand.chunk().getMetadata().getOrDefault("sourceName", "unknown");
                        String type = (String) cand.chunk().getMetadata().getOrDefault("sourceType", "");
                        aggregatedString.append(String.format("<CITATION id=\"%d\">\nSource: %s | Type: %s\n%s\n</CITATION>\n\n",
                                index++, name, type, cand.chunk().getText()));
                    }
                }
            } else {
                int index = 1;
                for (RetrievalCandidate cand : textEvidence) {
                    orderedCandidates.add(cand);
                    String name = (String) cand.chunk().getMetadata().getOrDefault("sourceName", "unknown");
                    String type = (String) cand.chunk().getMetadata().getOrDefault("sourceType", "");
                    aggregatedString.append(String.format("<CITATION id=\"%d\">\nSource: %s | Type: %s\n%s\n</CITATION>\n\n",
                            index++, name, type, cand.chunk().getText()));
                }
            }
        } else if (updatedVisuals.isEmpty()) {
            aggregatedString.append("No relevant evidence found.");
        }

        return new AggregatedEvidence(aggregatedString.toString().trim(), updatedVisuals, orderedCandidates);
    }
}
