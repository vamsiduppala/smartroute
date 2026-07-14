package com.vamsi.smartroute.gateway;

import com.vamsi.smartroute.governance.BudgetGuard;
import com.vamsi.smartroute.governance.RateLimiter;
import com.vamsi.smartroute.governance.SpendLedger;
import com.vamsi.smartroute.guardrails.PromptInjectionScanner;
import com.vamsi.smartroute.model.Tier;
import com.vamsi.smartroute.routing.ComplexityClassifier;
import com.vamsi.smartroute.routing.PartialRouteException;
import com.vamsi.smartroute.routing.RouteResult;
import com.vamsi.smartroute.routing.SmartRouteService;
import com.vamsi.smartroute.routing.Validator;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * The gateway pass — the layers wrapping the router, in order:
 *   1. guardrails: reject prompt-injection before it reaches a model;
 *   2. governance: estimate cost at the start tier and ATOMICALLY reserve it against the
 *      tenant's budget (BudgetGuard.evaluateAndReserve) -- closes a real TOCTOU race where two
 *      concurrent requests could otherwise both pass the check before either books spend;
 *   3. routing: delegate to SmartRouteService (tier selection + escalation);
 *   4. reconcile: true up the ledger from the reservation to the ACTUAL cost once known.
 * (Telemetry is recorded cross-cuttingly by RouterTelemetryAspect around step 3.)
 */
@Service
public class GatewayService {

    private final SmartRouteService router;
    private final PromptInjectionScanner scanner;
    private final BudgetGuard budgetGuard;
    private final SpendLedger ledger;
    private final ComplexityClassifier classifier;
    private final RateLimiter rateLimiter;

    public GatewayService(SmartRouteService router, PromptInjectionScanner scanner,
                          BudgetGuard budgetGuard, SpendLedger ledger, ComplexityClassifier classifier,
                          RateLimiter rateLimiter) {
        this.router = router;
        this.scanner = scanner;
        this.budgetGuard = budgetGuard;
        this.ledger = ledger;
        this.classifier = classifier;
        this.rateLimiter = rateLimiter;
    }

    public GatewayResult handle(String tenant, String prompt) {
        // Outermost gate: shed excess load per tenant before doing any scanning/classification work.
        if (!rateLimiter.tryAcquire(tenant)) {
            return GatewayResult.blocked("rate-limited",
                    List.of("burst=" + rateLimiter.capacity(), "refillPerSec=" + rateLimiter.refillPerSec()));
        }

        var scan = scanner.scan(prompt);
        if (scan.flagged()) {
            return GatewayResult.blocked("prompt-injection", scan.matched());
        }

        // Estimate cost at the classifier's start tier for a pre-spend budget check.
        var classification = classifier.classify(prompt);
        long estInputTokens = Math.max(1, prompt.length() / 4);   // ~4 chars/token
        long estOutputTokens = 256;
        double estimate = classification.startTier().costUsd(estInputTokens, estOutputTokens);

        // Atomically decide AND reserve `estimate` in one step -- see evaluateAndReserve's
        // javadoc for why a separate read-then-later-write was a real concurrency bug.
        var decision = budgetGuard.evaluateAndReserve(tenant, estimate);
        if (decision == BudgetGuard.Decision.REJECT) {
            return GatewayResult.blocked("budget-exceeded",
                    List.of("cap=$" + budgetGuard.capFor(tenant), "spent=$" + ledger.spent(tenant)));
        }

        // DOWNGRADE means "this tenant would go over cap at the classifier's tier" -- actually
        // force the cheapest tier instead of just labeling the response and routing normally.
        RouteResult result;
        try {
            result = decision == BudgetGuard.Decision.DOWNGRADE
                    ? router.routeFrom(prompt, Validator.nonEmpty(), Tier.LUNA)
                    : router.route(prompt, Validator.nonEmpty());
        } catch (PartialRouteException partial) {
            // An earlier attempt in this same request may have already incurred real cost
            // before the model call that ultimately failed -- true up the ledger from the
            // reservation to that partial actual cost before the failure propagates, or the
            // reservation permanently overstates (or understates) this tenant's real spend.
            ledger.add(tenant, partial.partialResult().costUsd() - estimate);
            throw partial;
        } catch (RuntimeException unexpected) {
            // Any OTHER failure out of routing (a validator or classifier that throws, or any
            // unchecked error the router's own try/catch doesn't wrap into PartialRouteException)
            // yields no RouteResult and no partial-cost snapshot -- nothing to reconcile against.
            // Fully REVERSE the up-front reservation before propagating: leaving it booked would
            // leak phantom spend that counts toward the tenant's cap and could eventually lock
            // them out of their own budget. The reservation must always be reconciled or reversed
            // (see BudgetGuard.evaluateAndReserve) -- this closes the last path that didn't.
            ledger.add(tenant, -estimate);
            throw unexpected;
        }
        ledger.add(tenant, result.costUsd() - estimate);   // true up: reservation -> actual cost
        return GatewayResult.ok(result, decision);
    }
}
