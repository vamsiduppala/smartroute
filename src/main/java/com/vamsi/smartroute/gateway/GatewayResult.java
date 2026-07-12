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
}
