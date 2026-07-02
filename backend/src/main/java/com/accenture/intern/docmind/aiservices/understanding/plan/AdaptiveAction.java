package com.accenture.intern.docmind.aiservices.understanding.plan;

import com.accenture.intern.docmind.aiservices.understanding.RetrievalPlan;
import java.util.Optional;

public record AdaptiveAction(
        AdaptiveActionType type,
        String reasoning,
        Optional<RetrievalPlan> nextPlan
) {
    public enum AdaptiveActionType {
        SEARCH,
        REFINE,
        EXPAND,
        SWITCH_SOURCE,
        STOP
    }
}
