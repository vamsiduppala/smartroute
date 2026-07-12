package com.vamsi.smartroute.observability;

import com.vamsi.smartroute.model.Tier;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

/**
 * Records per-call cost/latency/tier telemetry to Micrometer (and keeps in-memory aggregates for a
 * quick summary endpoint). Rides the AI SDK 7 telemetry redesign (2026-07-09) in spirit: make every
 * model call observable — tokens, cost, latency, tier — regardless of which module originated it.
 */
@Service
public class TelemetryService {

    private final MeterRegistry registry;
    private final Map<Tier, LongAdder> callsByTier = new ConcurrentHashMap<>();
    private final DoubleAdder totalCostUsd = new DoubleAdder();
    private final LongAdder totalCalls = new LongAdder();

    public TelemetryService(MeterRegistry registry) {
        this.registry = registry;
    }

    public void record(Tier tier, double costUsd, long latencyMs) {
        totalCalls.increment();
        totalCostUsd.add(costUsd);
        callsByTier.computeIfAbsent(tier, t -> new LongAdder()).increment();

        registry.counter("smartroute.calls", "tier", tier.name()).increment();
        registry.counter("smartroute.cost.usd", "tier", tier.name()).increment(costUsd);
        registry.timer("smartroute.latency", "tier", tier.name()).record(Duration.ofMillis(latencyMs));
    }

    public long totalCalls() { return totalCalls.sum(); }

    public double totalCostUsd() { return totalCostUsd.sum(); }

    public Map<String, Long> callsByTier() {
        Map<String, Long> m = new HashMap<>();
        callsByTier.forEach((t, a) -> m.put(t.name(), a.sum()));
        return m;
    }
}
