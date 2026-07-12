package com.vamsi.smartroute.governance;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Thread-safe running total of USD spent per tenant. Priced via {@link com.vamsi.smartroute.model.Tier#costUsd}. */
@Component
public class SpendLedger {

    private final Map<String, Double> spendByTenant = new ConcurrentHashMap<>();

    /** Add spend for a tenant, returning the new cumulative total. */
    public double add(String tenant, double usd) {
        return spendByTenant.merge(tenant, usd, Double::sum);
    }

    public double spent(String tenant) {
        return spendByTenant.getOrDefault(tenant, 0.0);
    }

    public Map<String, Double> snapshot() {
        return Map.copyOf(spendByTenant);
    }
}
