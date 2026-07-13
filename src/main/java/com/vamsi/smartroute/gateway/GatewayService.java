package com.vamsi.smartroute.gateway;

import com.vamsi.smartroute.governance.BudgetGuard;
import com.vamsi.smartroute.governance.SpendLedger;
import com.vamsi.smartroute.guardrails.PromptInjectionScanner;
import com.vamsi.smartroute.model.Tier;
import com.vamsi.smartroute.routing.ComplexityClassifier;
import com.vamsi.smartroute.routing.RouteResult;
import com.vamsi.smartroute.routing.SmartRouteService;
import com.vamsi.smartroute.routing.Validator;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * The gateway pass — the layers wrapping the router, in order:
 *   1. guardrails: reject prompt-injection before it reaches a model;
 *   2. governance: estimate cost at the start tier and reject if the tenant is over budget;
 *   3. routing: delegate to SmartRouteService (tier selection + escalation);
 *   4. book the actual spend to the tenant's ledger.
 * (Telemetry is recorded cross-cuttingly by RouterTelemetryAspect around step 3.)
 */
@Service
public class GatewayService {

    private final SmartRouteService router;
    private final PromptInjectionScanner scanner;
    private final BudgetGuard budgetGuard;
    private final SpendLedger ledger;
    private final ComplexityClassifier classifier;

    public GatewayService(SmartRouteService router, PromptInjectionScanner scanner,
                          BudgetGuard budgetGuard, SpendLedger ledger, ComplexityClassifier classifier) {
        this.router = router;
        this.scanner = scanner;
        this.budgetGuard = budgetGuard;
        this.ledger = ledger;
        this.classifier = classifier;
    }

    public GatewayResult handle(String tenant, String prompt) {
        var scan = scanner.scan(prompt);
        if (scan.flagged()) {
            return GatewayResult.blocked("prompt-injection", scan.matched());
        }

        // Estimate cost at the classifier's start tier for a pre-spend budget check.
        var classification = classifier.classify(prompt);
        long estInputTokens = Math.max(1, prompt.length() / 4);   // ~4 chars/token
        long estOutputTokens = 256;
        double estimate = classification.startTier().costUsd(estInputTokens, estOutputTokens);

        var decision = budgetGuard.evaluate(tenant, estimate);
        if (decision == BudgetGuard.Decision.REJECT) {
            return GatewayResult.blocked("budget-exceeded",
                    List.of("cap=$" + budgetGuard.capFor(tenant), "spent=$" + ledger.spent(tenant)));
        }

        // DOWNGRADE means "this tenant would go over cap at the classifier's tier" -- actually
        // force the cheapest tier instead of just labeling the response and routing normally.
        RouteResult result = decision == BudgetGuard.Decision.DOWNGRADE
                ? router.routeFrom(prompt, Validator.nonEmpty(), Tier.LUNA)
                : router.route(prompt, Validator.nonEmpty());
        ledger.add(tenant, result.costUsd());   // book actual spend
        return GatewayResult.ok(result, decision);
    }
}
