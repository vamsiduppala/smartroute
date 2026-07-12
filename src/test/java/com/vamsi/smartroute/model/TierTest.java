package com.vamsi.smartroute.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TierTest {

    @Test
    void costIsPricePerMillionTokens() {
        // 1M in + 1M out at Luna rates = $1 + $6
        assertEquals(7.0, Tier.LUNA.costUsd(1_000_000, 1_000_000), 1e-9);
        // Sol is the 5x tier: $5 + $30
        assertEquals(35.0, Tier.SOL.costUsd(1_000_000, 1_000_000), 1e-9);
    }

    @Test
    void escalationClimbsThenStops() {
        assertEquals(Tier.TERRA, Tier.LUNA.escalate());
        assertEquals(Tier.SOL, Tier.TERRA.escalate());
        assertNull(Tier.SOL.escalate(), "Sol is the top tier; nothing above it");
    }

    @Test
    void zeroTokensCostNothing() {
        assertEquals(0.0, Tier.TERRA.costUsd(0, 0), 1e-9);
    }
}
