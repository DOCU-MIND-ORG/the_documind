package com.accenture.intern.docmind.aiservices.understanding.plan;

import com.accenture.intern.docmind.dto.retrieval.RankingExplanation;
import java.util.List;

public record RetrievalObservation(
        ObservationType type,
        String message,
        List<RankingExplanation> evidenceSummaries,
        RetrievalGap identifiedGap
) {
}
