package com.vamsi.smartroute.gateway;

import com.vamsi.smartroute.governance.BudgetGuard;
import com.vamsi.smartroute.routing.RouteResult;

import java.util.List;

/** Outcome of a full gateway pass: guardrails + budget check + (if allowed) the routed result. */
public record GatewayResult(
        boolean allowed,
        String status,
        RouteResult route,
        String budgetDecision,
        List<String> notes
) {
    public static GatewayResult blocked(String reason, List<String> notes) {
        return new GatewayResult(false, reason, null, null, notes);
    }

    public static GatewayResult ok(RouteResult route, BudgetGuard.Decision decision) {
        return new GatewayResult(true, "ok", route, decision.name(), List.of());
    }

    /**
     * An exact-match cache hit: the prompt was answered before, so this response cost $0 rather
     * than re-routing. {@code budgetDecision="CACHED"} and the note carries the amount saved (the
     * original compute cost of the cached answer) — the cost win, stated on the response itself.
     */
    public static GatewayResult cached(RouteResult route) {
        return new GatewayResult(true, "ok", route, "CACHED",
                List.of(String.format("cache-hit: saved $%.5f", route.costUsd())));
    }
}
