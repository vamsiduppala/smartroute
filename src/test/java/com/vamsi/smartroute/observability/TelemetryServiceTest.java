package com.vamsi.smartroute.observability;

import com.vamsi.smartroute.model.Tier;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TelemetryServiceTest {

    @Test
    void aggregatesCallsAndCostAcrossTiers() {
        TelemetryService t = new TelemetryService(new SimpleMeterRegistry());
        t.record(Tier.LUNA, 0.001, 120);
        t.record(Tier.LUNA, 0.002, 130);
        t.record(Tier.SOL, 0.010, 400);

        assertEquals(3, t.totalCalls());
        assertEquals(0.013, t.totalCostUsd(), 1e-9);
        assertEquals(2L, t.callsByTier().get("LUNA"));
        assertEquals(1L, t.callsByTier().get("SOL"));
    }

    @Test
    void startsEmpty() {
        TelemetryService t = new TelemetryService(new SimpleMeterRegistry());
        assertEquals(0, t.totalCalls());
        assertEquals(0.0, t.totalCostUsd(), 1e-9);
        assertTrue(t.callsByTier().isEmpty());
    }
}
