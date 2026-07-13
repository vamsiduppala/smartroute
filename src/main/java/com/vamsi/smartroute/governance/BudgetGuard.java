package com.vamsi.smartroute.governance;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enforces per-tenant spend caps BEFORE any tokens are spent — the governance layer of the gateway.
 * Rides GPT-5.6 tier pricing (2026-07-09): the 5x spread between Luna and Sol is exactly what makes
 * a "downgrade instead of reject" decision worth automating.
 */
@Component
public class BudgetGuard {

    public enum Decision { ALLOW, DOWNGRADE, REJECT }

    private final SpendLedger ledger;
    private final Map<String, Double> caps = new ConcurrentHashMap<>();
    private final double defaultCap;

    public BudgetGuard(SpendLedger ledger,
                       @Value("${smartroute.governance.default-cap-usd:10.0}") double defaultCap) {
        this.ledger = ledger;
        this.defaultCap = defaultCap;
    }

    public void setCap(String tenant, double capUsd) { caps.put(tenant, capUsd); }

    public double capFor(String tenant) { return caps.getOrDefault(tenant, defaultCap); }

    /**
     * Decide before spending, AND atomically reserve {@code estimatedCostUsd} against the
     * tenant's ledger in the same step (unless REJECT) -- closing a TOCTOU race where two
     * concurrent callers could otherwise both read the same pre-spend total and both get
     * admitted. Named "...AndReserve" deliberately: this has a side effect, unlike a plain
     * "evaluate". Caller MUST reconcile afterward via
     * {@code ledger.add(tenant, actualCostUsd - estimatedCostUsd)} once the real cost is known
     * (see GatewayService), or the reservation permanently overstates the tenant's spend.
     *
     *  REJECT    — tenant already at/over cap. Nothing reserved.
     *  DOWNGRADE — this call would push them over (route to a cheaper tier instead). Reserved.
     *  ALLOW     — comfortably under cap. Reserved.
     */
    public Decision evaluateAndReserve(String tenant, double estimatedCostUsd) {
        return ledger.reserveIfWithinCap(tenant, estimatedCostUsd, capFor(tenant));
    }
}
