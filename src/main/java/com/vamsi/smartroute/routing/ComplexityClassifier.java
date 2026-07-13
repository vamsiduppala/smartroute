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
            // Stems carry inflection tolerance so common real-world phrasings actually fire.
            // Two lessons baked in here, both found by evidence not guesswork:
            //   * "concurren\\w*" (not bare "concurren"): a trailing \b requires "concurren" to
            //     be a complete word, and it never is -- always "concurrent"/"concurrency"/etc.
            //     Without \w* this branch could fire on nothing (independent-review finding;
            //     regression: ComplexityClassifierTest.concurrencyRelatedPromptsRegisterAsAHardSignal).
            //   * The calibration corpus (docs/classifier-calibration.md) showed the bare stems
            //     "derive"/"architect"/"refactor"/"optimi[sz]e"/"trade-off" silently missing the
            //     everyday inflections "deriving"/"derivation", "architecture", "refactoring",
            //     "optimizing"/"optimization", and plural "trade-offs" -- so a proof or
            //     architecture task read as trivial and started at Luna. Each stem below is
            //     widened only as far as stays false-positive-safe: the leading \b still blocks
            //     substring hits like "improve"/"approve" containing "prov", and the stems
            //     (deriv/refactor/architect/recursi/complexit/algorithm) are specific enough that
            //     \w* admits only genuine inflections, never an unrelated word.
            //   * The suffix-list stems carry an optional trailing "s?" so PLURAL noun forms fire
            //     too -- "proofs"/"optimizations"/"derivations"/"regexes". Without it the single
            //     trailing \b needs a boundary right after the suffix, so the plural silently missed
            //     while the sibling "trade-offs" (which had s?) worked (independent-review finding).
            "\\b(prov(e|es|ed|ing|en)|proofs?|deriv(e|es|ed|ing|ations?)|optimi[sz](e|es|ed|ing|ations?)|"
          + "refactor\\w*|concurren\\w*|race condition|algorithm\\w*|complexit\\w*|regex(es)?|"
          + "recursi\\w*|multi-step|step by step|architect\\w*|design a|trade-?offs?)\\b",
            Pattern.CASE_INSENSITIVE);

    // "class [A-Z]\\w+" (not "class \\w+"): a Java class name is PascalCase, so requiring an
    // uppercase initial matches real declarations ("class Foo") while dropping everyday prose like
    // "yoga class starts" / "class action lawsuit", which otherwise scored a spurious code point
    // (independent-review finding). No CASE_INSENSITIVE flag here, so [A-Z] is genuinely uppercase.
    private static final Pattern CODE_FENCE = Pattern.compile("```|\\bclass [A-Z]\\w+|\\bpublic static\\b");

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
