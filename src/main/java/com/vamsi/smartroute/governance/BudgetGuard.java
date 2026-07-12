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
     * Decide before spending:
     *  REJECT    — tenant already at/over cap;
     *  DOWNGRADE — this call would push them over (route to a cheaper tier instead);
     *  ALLOW     — comfortably under cap.
     */
    public Decision evaluate(String tenant, double estimatedCostUsd) {
        double spent = ledger.spent(tenant);
        double cap = capFor(tenant);
        if (spent >= cap) return Decision.REJECT;
        if (spent + estimatedCostUsd > cap) return Decision.DOWNGRADE;
        return Decision.ALLOW;
    }
}
