package com.vamsi.smartroute.routing;

import com.vamsi.smartroute.model.Tier;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Cheap, explainable heuristic that picks a STARTING tier from the prompt alone —
 * no model call. The router escalates from here if the answer fails validation, so
 * this only needs to be roughly right: err toward the cheaper tier and let escalation
 * catch the misses.
 */
@Component
public class ComplexityClassifier {

    private static final Pattern HARD_SIGNALS = Pattern.compile(
            "\\b(prove|derive|optimi[sz]e|refactor|concurren|race condition|"
          + "algorithm|complexity|regex|recursion|multi-step|step by step|"
          + "architect|design a|trade-?off)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern CODE_FENCE = Pattern.compile("```|\\bclass \\w+|\\bpublic static\\b");

    public Classification classify(String prompt) {
        int score = 0;
        String reason;

        int len = prompt.length();
        long hardHits = HARD_SIGNALS.matcher(prompt).results().count();
        boolean hasCode = CODE_FENCE.matcher(prompt).find();

        if (len > 1200) score += 2;
        else if (len > 400) score += 1;
        if (hasCode) score += 1;
        score += (int) Math.min(hardHits, 3);

        Tier start;
        if (score >= 4) { start = Tier.SOL;   reason = "high-complexity signals (score=" + score + ")"; }
        else if (score >= 2) { start = Tier.TERRA; reason = "moderate complexity (score=" + score + ")"; }
        else { start = Tier.LUNA; reason = "simple/short prompt (score=" + score + ")"; }

        return new Classification(start, score, reason);
    }

    public record Classification(Tier startTier, int score, String reason) {}
}
