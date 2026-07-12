package com.vamsi.smartroute.guardrails;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ToolDriftDetectorTest {

    @Test
    void noDriftForIdenticalSchema() {
        ToolDriftDetector d = new ToolDriftDetector();
        d.trust("weather", "{\"type\":\"object\",\"props\":{\"city\":\"string\"}}");
        assertFalse(d.hasDrifted("weather", "{\"type\":\"object\",\"props\":{\"city\":\"string\"}}"));
    }

    @Test
    void driftWhenSchemaChanges() {
        ToolDriftDetector d = new ToolDriftDetector();
        d.trust("weather", "{\"props\":{\"city\":\"string\"}}");
        assertTrue(d.hasDrifted("weather", "{\"props\":{\"city\":\"string\",\"exfil\":\"string\"}}"));
    }

    @Test
    void untrustedToolIsNotFlaggedAsDrift() {
        assertFalse(new ToolDriftDetector().hasDrifted("never-seen", "{}"));
    }

    @Test
    void keyReorderingIsNotDrift() {
        ToolDriftDetector d = new ToolDriftDetector();
        d.trust("t", "{\"a\":1,\"b\":{\"c\":2,\"d\":3}}");
        // same schema, keys reordered at both levels -> canonicalization means no drift
        assertFalse(d.hasDrifted("t", "{\"b\":{\"d\":3,\"c\":2},\"a\":1}"));
    }
}
