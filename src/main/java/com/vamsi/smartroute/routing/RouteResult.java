package com.vamsi.smartroute.routing;

import com.vamsi.smartroute.model.Tier;

/** Outcome of one routed request, including which tier finally answered and what it cost. */
public record RouteResult(
        String answer,
        Tier startTier,
        Tier tierUsed,
        int attempts,
        long inputTokens,
        long outputTokens,
        double costUsd,
        boolean passed,
        String classifierReason
) {}
