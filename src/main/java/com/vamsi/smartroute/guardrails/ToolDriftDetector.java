package com.vamsi.smartroute.guardrails;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Java port of the AI SDK 7 tool-drift defense (fingerprintTools / detectToolDrift, 2026-07-09).
 * Fingerprint an MCP tool's schema when you first trust it; if the schema silently mutates on a
 * later turn ("rug pull"), the fingerprint no longer matches and the call can be refused.
 *
 * Schemas are CANONICALIZED (JSON object keys sorted) before hashing, so a server that merely
 * reorders keys between calls does not trigger a false drift alarm.
 */
@Component
public class ToolDriftDetector {

    // FAIL_ON_TRAILING_TOKENS is off by default in Jackson, which silently ignores anything
    // after a valid JSON value -- e.g. readValue would happily parse "{\"a\":1} anything-here"
    // as just {"a":1}. Left at the default, a schema that changed ONLY by appending content
    // after valid JSON would canonicalize identically to the original and defeat hasDrifted()
    // entirely -- exactly the "rug pull" scenario this class exists to catch.
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, true);

    private final Map<String, String> trusted = new ConcurrentHashMap<>();

    /** Sort JSON object keys recursively so semantically-identical schemas hash the same. Non-JSON hashes raw. */
    static String canonicalize(String json) {
        if (json == null) return "";
        try {
            Object parsed = MAPPER.readValue(json, Object.class);
            return MAPPER.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(parsed);
        } catch (Exception notJson) {
            return json.trim();
        }
    }

    public static String fingerprint(String schemaJson) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(canonicalize(schemaJson).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Record and trust a tool's current schema. Returns the fingerprint. */
    public String trust(String toolName, String schemaJson) {
        String fp = fingerprint(schemaJson);
        trusted.put(toolName, fp);
        return fp;
    }

    /** True if the tool's schema differs from what was trusted (drift). Unknown tools are not "drift". */
    public boolean hasDrifted(String toolName, String schemaJson) {
        String known = trusted.get(toolName);
        return known != null && !known.equals(fingerprint(schemaJson));
    }

    public boolean isTrusted(String toolName) { return trusted.containsKey(toolName); }
}
