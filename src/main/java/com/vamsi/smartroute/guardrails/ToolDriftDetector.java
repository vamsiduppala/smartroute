package com.vamsi.smartroute.guardrails;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Java port of the AI SDK 7 tool-drift defense (fingerprintTools / detectToolDrift, 2026-07-09).
 * You fingerprint an MCP tool's schema when you first trust it; if the schema silently mutates on a
 * later turn ("rug pull"), the fingerprint no longer matches and the call can be refused.
 *
 * This is a Spring/Java implementation of that same idea — not the AI SDK itself.
 */
@Component
public class ToolDriftDetector {

    private final Map<String, String> trusted = new ConcurrentHashMap<>();

    public static String fingerprint(String schemaJson) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(schemaJson.getBytes(StandardCharsets.UTF_8));
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
