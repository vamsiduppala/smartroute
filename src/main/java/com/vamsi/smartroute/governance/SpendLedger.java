package com.vamsi.smartroute.governance;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Thread-safe running total of USD spent per tenant. Priced via {@link com.vamsi.smartroute.model.Tier#costUsd}. */
@Component
public class SpendLedger {

    private final Map<String, Double> spendByTenant = new ConcurrentHashMap<>();

    /** Add spend for a tenant, returning the new cumulative total. Delta may be negative (reconciling a reservation). */
    public double add(String tenant, double usd) {
        return spendByTenant.merge(tenant, usd, Double::sum);
    }

    public double spent(String tenant) {
        return spendByTenant.getOrDefault(tenant, 0.0);
    }

    public Map<String, Double> snapshot() {
        return Map.copyOf(spendByTenant);
    }

    /**
     * Atomically decide ALLOW/DOWNGRADE/REJECT against {@code cap} and, unless REJECT,
     * immediately book {@code estimatedCostUsd} as a provisional reservation in the SAME atomic
     * step as the decision -- see {@link BudgetGuard#evaluateAndReserve}.
     *
     * This exists to close a real TOCTOU race: read-spent-then-later-write-actual-spend (the
     * original design) let two concurrent requests for the same tenant both read the same
     * pre-spend total, both get admitted, and both book spend afterward -- cumulative spend
     * could exceed the cap under concurrent load, even though this map's individual operations
     * were always thread-safe. Reserving inside the same {@code compute} that makes the
     * admission decision closes that window; the caller reconciles afterward with
     * {@code add(tenant, actualCostUsd - estimatedCostUsd)} once the real cost is known.
     */
    BudgetGuard.Decision reserveIfWithinCap(String tenant, double estimatedCostUsd, double cap) {
        BudgetGuard.Decision[] decisionHolder = new BudgetGuard.Decision[1];
        spendByTenant.compute(tenant, (t, currentSpent) -> {
            double spent = currentSpent == null ? 0.0 : currentSpent;
            if (spent >= cap) {
                decisionHolder[0] = BudgetGuard.Decision.REJECT;
                return currentSpent;   // no reservation -- request is rejected outright
            }
            decisionHolder[0] = (spent + estimatedCostUsd > cap)
                    ? BudgetGuard.Decision.DOWNGRADE
                    : BudgetGuard.Decision.ALLOW;
            return spent + estimatedCostUsd;   // reserve optimistically either way
        });
        return decisionHolder[0];
    }
}
