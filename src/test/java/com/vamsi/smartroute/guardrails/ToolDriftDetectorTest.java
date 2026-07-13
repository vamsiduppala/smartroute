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

    @Test
    void keyReorderingIsStillNotDriftEvenWithTrailingContent() {
        // Regression coverage for a bug an earlier fix in this detector introduced: a prior version
        // used DeserializationFeature.FAIL_ON_TRAILING_TOKENS, which made canonicalize() throw
        // on trailing content -- falling into the SAME catch block used for non-JSON input
        // (raw trimmed-string comparison, which IS key-order sensitive). That broke
        // keyReorderingIsNotDrift as soon as trailing content was involved: reordering keys in
        // a schema with trailing text produced a false drift alarm. Confirmed by temporarily
        // reverting to that approach and observing this assertion fail (drifted was true).
        ToolDriftDetector d = new ToolDriftDetector();
        d.trust("t", "{\"a\":1,\"b\":2} trailing");
        assertFalse(d.hasDrifted("t", "{\"b\":2,\"a\":1} trailing"));
    }

    @Test
    void driftIsDetectedWhenContentIsAppendedAfterValidJson() {
        // Regression coverage for the original bug (found via independent review, confirmed by
        // observing two such fingerprints come out equal on the unfixed version): Jackson's
        // ObjectMapper.readValue does not fail on trailing tokens by default, so plain
        // readValue() happily parses "{\"a\":1} anything-here" as just {"a":1} -- a schema that
        // changed ONLY by appending content after valid JSON would canonicalize identically to
        // the original and silently defeat hasDrifted() entirely.
        ToolDriftDetector d = new ToolDriftDetector();
        d.trust("weather", "{\"type\":\"object\"}");
        assertTrue(d.hasDrifted("weather", "{\"type\":\"object\"} malicious-trailing-content"));
    }

    @Test
    void trailingWhitespaceOnlyIsNotTreatedAsChangedContent() {
        ToolDriftDetector d = new ToolDriftDetector();
        d.trust("weather", "{\"type\":\"object\"}");
        assertFalse(d.hasDrifted("weather", "{\"type\":\"object\"}\n  \n"));
    }
}
