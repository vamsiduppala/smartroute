package com.vamsi.smartroute.governance;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Per-tenant request rate limiting — the other half of governance next to {@link BudgetGuard}
 * (cost caps). A gateway that sits in front of every LLM call has to bound request *rate*, not
 * just spend: a tenant can hammer cheap-tier calls that each pass the budget check individually
 * yet still overwhelm capacity.
 *
 * <p>Token bucket per tenant: a steady refill of {@code refillPerSec} tokens up to a {@code
 * capacity} burst. Each admitted request consumes one token; when the bucket is empty the request
 * is refused. Bucket state is kept behind a single {@link ConcurrentHashMap#compute} per tenant,
 * so the refill-and-consume is one atomic step — two concurrent requests for the same tenant can't
 * both read the same token count and both get admitted (the same care {@code SpendLedger} takes
 * for spend).
 *
 * <p>Time is injected as a {@link LongSupplier} of nanoseconds so tests advance it deterministically
 * instead of sleeping — no wall-clock flakiness.
 */
@Component
public class RateLimiter {

    /** Idle-tenant sweep cap: buckets are reclaimed once the map grows past this many tenants. */
    private static final int DEFAULT_MAX_TENANTS = 100_000;

    private final double capacity;
    private final double refillPerSec;
    private final int maxTenants;
    private final LongSupplier nanoClock;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    /** Immutable bucket snapshot; a new one is returned from each atomic {@code compute}. */
    private record Bucket(double tokens, long lastRefillNanos) {}

    @Autowired
    public RateLimiter(
            @Value("${smartroute.governance.rate.capacity:20}") double capacity,
            @Value("${smartroute.governance.rate.refill-per-sec:10}") double refillPerSec) {
        this(capacity, refillPerSec, DEFAULT_MAX_TENANTS, System::nanoTime);
    }

    /** Test seam: inject a controllable nanosecond clock (default tenant cap). */
    RateLimiter(double capacity, double refillPerSec, LongSupplier nanoClock) {
        this(capacity, refillPerSec, DEFAULT_MAX_TENANTS, nanoClock);
    }

    /** Test seam: also control the tenant cap so idle-bucket eviction can be exercised. */
    RateLimiter(double capacity, double refillPerSec, int maxTenants, LongSupplier nanoClock) {
        this.capacity = capacity;
        this.refillPerSec = refillPerSec;
        this.maxTenants = maxTenants;
        this.nanoClock = nanoClock;
    }

    /**
     * Refill the tenant's bucket for the elapsed time, then consume one token if any is available.
     * Returns true if the request is admitted, false if the tenant is currently rate-limited.
     */
    public boolean tryAcquire(String tenant) {
        long now = nanoClock.getAsLong();
        boolean[] granted = {false};
        buckets.compute(tenant, (t, b) -> {
            double tokens;
            if (b == null) {
                tokens = capacity;                 // first request: full burst available
            } else {
                double refill = (now - b.lastRefillNanos()) / 1_000_000_000.0 * refillPerSec;
                tokens = Math.min(capacity, b.tokens() + refill);
            }
            if (tokens >= 1.0) {
                granted[0] = true;
                return new Bucket(tokens - 1.0, now);
            }
            return new Bucket(tokens, now);        // empty: refuse, but keep the refreshed timestamp
        });
        // Bound memory: without this the map keeps a permanent entry per distinct tenant string
        // (tenant ids come from the request, so a high-cardinality/spoofed value could grow it
        // without limit -- ironic for a load-shedding component). Only sweep when over the cap.
        if (buckets.size() > maxTenants) {
            evictIdleBuckets(now);
        }
        return granted[0];
    }

    /**
     * Drop buckets that have fully refilled back to {@code capacity}: such a bucket is
     * indistinguishable from having no entry at all (the tenant's next request would recreate it at
     * full capacity), so removing it is safe and never weakens the limit. Active (partially-drained)
     * tenants are kept. O(n) but only runs while the map is over its cap.
     */
    private void evictIdleBuckets(long now) {
        buckets.entrySet().removeIf(e -> {
            Bucket b = e.getValue();
            double refilled = b.tokens() + (now - b.lastRefillNanos()) / 1_000_000_000.0 * refillPerSec;
            return refilled >= capacity;
        });
    }

    public double capacity() { return capacity; }

    public double refillPerSec() { return refillPerSec; }

    /** Number of tenants currently tracked — for tests/introspection of the memory bound. */
    int trackedTenants() { return buckets.size(); }
}
