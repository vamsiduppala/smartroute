package com.vamsi.smartroute.model;

/**
 * The three GPT-5.6 tiers (launched 2026-07-09), cheapest to most capable.
 * Prices are USD per million tokens as published at launch.
 *
 * Deliberately hardcoded here, not sourced from application.yml: an earlier
 * `smartroute.tiers.*` YAML section duplicated these exact values but was never actually wired
 * up to anything (found via independent review — no @ConfigurationProperties/@Value bound to
 * it anywhere), so editing it would silently do nothing while looking like it controlled
 * pricing. Removed rather than wired up: making pricing genuinely config-driven would mean
 * this enum can no longer just BE the pricing (enums are instantiated before Spring's context
 * exists), a real architectural change across every `Tier.X` call site in the app for a
 * "what if OpenAI changes prices" scenario that hasn't come up. If GPT-5.6 pricing changes,
 * update the three lines below and re-verify against OpenAI's release notes.
 */
public enum Tier {
    LUNA("gpt-5.6-luna", 1.0, 6.0),
    TERRA("gpt-5.6-terra", 2.5, 15.0),
    SOL("gpt-5.6-sol", 5.0, 30.0);

    public final String modelId;
    public final double inputPerMTok;
    public final double outputPerMTok;

    Tier(String modelId, double inputPerMTok, double outputPerMTok) {
        this.modelId = modelId;
        this.inputPerMTok = inputPerMTok;
        this.outputPerMTok = outputPerMTok;
    }

    /** Next tier up, or null if already at the top. */
    public Tier escalate() {
        return switch (this) {
            case LUNA -> TERRA;
            case TERRA -> SOL;
            case SOL -> null;
        };
    }

    public double costUsd(long inputTokens, long outputTokens) {
        return (inputTokens / 1_000_000.0) * inputPerMTok
             + (outputTokens / 1_000_000.0) * outputPerMTok;
    }
}
