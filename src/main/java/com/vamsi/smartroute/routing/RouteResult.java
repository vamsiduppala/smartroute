package com.vamsi.smartroute.routing;

import com.vamsi.smartroute.model.Tier;

import java.util.List;

/**
 * Outcome of one routed request, including which tier finally answered and what it cost.
 * {@code attemptRecords} carries one entry per actual model call that got a response (see
 * {@link AttemptRecord}) -- {@code inputTokens}/{@code outputTokens}/{@code costUsd} remain the
 * pre-existing AGGREGATE across every attempt, kept for backward compatibility with existing
 * callers; the per-attempt breakdown is what fixed the observability granularity gap (see
 * observability-NOTES.md).
 */
public record RouteResult(
        String answer,
        Tier startTier,
        Tier tierUsed,
        int attempts,
        long inputTokens,
        long outputTokens,
        double costUsd,
        boolean passed,
        String classifierReason,
        List<AttemptRecord> attemptRecords
) {
    /** Convenience constructor for callers (mostly tests) that don't care about the per-attempt breakdown. */
    public RouteResult(String answer, Tier startTier, Tier tierUsed, int attempts,
                        long inputTokens, long outputTokens, double costUsd,
                        boolean passed, String classifierReason) {
        this(answer, startTier, tierUsed, attempts, inputTokens, outputTokens, costUsd,
                passed, classifierReason, List.of());
    }
}
