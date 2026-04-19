package org.finos.legend.engine.nlq;

import java.util.List;

/**
 * Result of the NLQ-to-Pure pipeline.
 */
public record NlqResult(
        String rootClass,
        String queryPlan,
        String pureQuery,
        String explanation,
        boolean isValid,
        String validationError,
        List<String> retrievedClasses,
        long latencyMs,
        boolean cannotAnswer,
        String followUpQuestion
) {
    public static NlqResult error(String error, List<String> retrievedClasses, long latencyMs) {
        return new NlqResult(null, null, null, null, false, error, retrievedClasses, latencyMs, false, null);
    }

    public static NlqResult decline(String followUpQuestion, List<String> retrievedClasses, long latencyMs) {
        return new NlqResult(null, null, null, "Declined — follow-up question suggested", true, null, retrievedClasses, latencyMs, true, followUpQuestion);
    }
}
