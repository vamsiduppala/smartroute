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
}
