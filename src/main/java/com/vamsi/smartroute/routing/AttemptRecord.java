package com.vamsi.smartroute.routing;

import com.vamsi.smartroute.model.Tier;

/**
 * One real model call within an escalation: which tier, how many tokens, what it cost, how
 * long it took. {@link RouteResult#attemptRecords()} carries one of these per attempt that
 * actually got a response (a failed attempt -- see {@link PartialRouteException} -- never
 * produces one, since no tokens came back).
 */
public record AttemptRecord(Tier tier, long inputTokens, long outputTokens, double costUsd, long latencyMs) {}
