package com.vamsi.smartroute.routing;

import com.vamsi.smartroute.model.Tier;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * The router. Classifies a prompt to a starting tier, then walks UP the tiers
 * (Luna -> Terra -> Sol) until an answer passes the validator, accumulating token
 * cost across attempts. This is the whole thesis: pay Sol prices only when the
 * cheaper tiers actually fail, not by default.
 */
@Service
public class SmartRouteService {

    private final OpenAiChatModel chatModel;
    private final ComplexityClassifier classifier;

    public SmartRouteService(OpenAiChatModel chatModel, ComplexityClassifier classifier) {
        this.chatModel = chatModel;
        this.classifier = classifier;
    }

    public RouteResult route(String prompt, Validator validator) {
        var classification = classifier.classify(prompt);
        return routeFrom(prompt, validator, classification.startTier(), classification.reason());
    }

    /**
     * Force a starting tier instead of letting the classifier pick one — e.g. governance
     * calling this with {@link Tier#LUNA} to actually carry out a BudgetGuard DOWNGRADE
     * decision, rather than just labeling the response and routing normally anyway.
     */
    public RouteResult routeFrom(String prompt, Validator validator, Tier startTier) {
        return routeFrom(prompt, validator, startTier, "forced start tier: " + startTier);
    }

    private RouteResult routeFrom(String prompt, Validator validator, Tier startTier, String startReason) {
        Tier tier = startTier;

        long totalIn = 0, totalOut = 0;
        double totalCost = 0.0;   // billed per attempt at THAT attempt's tier rate
        int attempts = 0;
        String answer = "";
        boolean passed = false;
        Tier used = tier;
        List<AttemptRecord> records = new ArrayList<>();

        while (tier != null) {
            attempts++;
            used = tier;

            var options = OpenAiChatOptions.builder().model(tier.modelId).build();
            ChatResponse response;
            long attemptStart = System.nanoTime();
            try {
                response = chatModel.call(new Prompt(prompt, options));
            } catch (RuntimeException modelCallFailed) {
                // Earlier attempts this loop (if any) may have already succeeded and incurred
                // real cost -- surface it via PartialRouteException instead of losing it to a
                // bare exception. totalIn/totalOut/totalCost/records only reflect attempts
                // BEFORE this one; this attempt itself contributed no tokens since it never got
                // a response, so it never gets an AttemptRecord.
                RouteResult partial = new RouteResult(answer, startTier, used, attempts,
                        totalIn, totalOut, totalCost, false, startReason, List.copyOf(records));
                throw new PartialRouteException(partial, modelCallFailed);
            }
            long attemptLatencyMs = (System.nanoTime() - attemptStart) / 1_000_000;

            answer = response.getResult().getOutput().getText();
            var usage = response.getMetadata().getUsage();
            long in = asLong(usage.getPromptTokens());
            long out = asLong(usage.getCompletionTokens());
            double attemptCost = tier.costUsd(in, out);   // each failed attempt still costs — at its own rate
            totalIn += in;
            totalOut += out;
            totalCost += attemptCost;
            records.add(new AttemptRecord(tier, in, out, attemptCost, attemptLatencyMs));

            if (validator.accepts(answer)) { passed = true; break; }
            tier = tier.escalate();   // cheaper tier failed the check — climb one rung
        }

        return new RouteResult(answer, startTier, used, attempts,
                totalIn, totalOut, totalCost, passed, startReason, List.copyOf(records));
    }

    private static long asLong(Object tokenCount) {
        // Spring AI's Usage token getters have shifted between Integer/Long across versions —
        // normalize defensively. See BUILD_NOTES.md ("gotchas").
        if (tokenCount == null) return 0L;
        return ((Number) tokenCount).longValue();
    }
}
