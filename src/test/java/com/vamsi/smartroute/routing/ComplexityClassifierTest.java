package com.vamsi.smartroute.routing;

import com.vamsi.smartroute.model.Tier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ComplexityClassifierTest {

    private final ComplexityClassifier classifier = new ComplexityClassifier();

    @Test
    void trivialPromptStartsAtLuna() {
        // no hard signals, short, no code -> score 0
        assertEquals(Tier.LUNA, classifier.classify("What is 2+2?").startTier());
    }

    @Test
    void moderatePromptStartsAtTerra() {
        // two hard signals ("trade-off", "algorithm"), short, no code -> score 2
        var c = classifier.classify("Explain the trade-off and the algorithm here.");
        assertEquals(2, c.score());
        assertEquals(Tier.TERRA, c.startTier());
    }

    @Test
    void hardPromptWithCodeStartsAtSol() {
        // optimize/algorithm/prove/complexity (capped at 3) + code fence (+1) -> score 4
        var c = classifier.classify("Optimize this algorithm and prove its complexity: ```code```");
        assertTrue(c.score() >= 4, "expected high-complexity score, got " + c.score());
        assertEquals(Tier.SOL, c.startTier());
    }

    @Test
    void moderateLengthAloneIsNotEnoughToLeaveLuna() {
        // length > 400 but <= 1200 -> +1 only, no hard signals/code -> score 1, still LUNA
        String prompt = "word ".repeat(90);   // ~450 chars, no keywords, no code
        var c = classifier.classify(prompt);
        assertEquals(1, c.score());
        assertEquals(Tier.LUNA, c.startTier());
    }

    @Test
    void greatLengthAloneIsEnoughToReachTerra() {
        // length > 1200 -> +2 on its own, no hard signals/code needed -> score 2 -> Terra
        String prompt = "word ".repeat(300);   // ~1500 chars, no keywords, no code
        var c = classifier.classify(prompt);
        assertEquals(2, c.score());
        assertEquals(Tier.TERRA, c.startTier());
    }

    @Test
    void inflectedHardSignalsRegister() {
        // Calibration finding (docs/classifier-calibration.md): the bare stems missed everyday
        // inflected phrasings, so proof/architecture/refactor tasks read as trivial. Each of these
        // must now register at least one hard signal.
        for (String prompt : new String[]{
                "Prove this by deriving each step of the derivation.",   // proving/deriving/derivation
                "Design the architecture and its architectural layers.", // architecture/architectural
                "Refactoring this while optimizing the hot path.",       // refactoring/optimizing
                "Weigh the trade-offs of this optimization.",            // trade-offs (plural)/optimization
                "Analyze the recursion and the algorithms involved."     // recursion/algorithms
        }) {
            assertTrue(classifier.classify(prompt).score() >= 1,
                    "expected a hard signal to fire on: " + prompt);
        }
    }

    @Test
    void inflectionToleranceDoesNotFalsePositiveOnUnrelatedWords() {
        // The widened stems must not swallow common words that merely CONTAIN a stem: "improve",
        // "approve", "provide" all contain "prov" but are not proofs; the leading \b blocks them.
        var c = classifier.classify("Please improve and approve this; it will provide value to users.");
        assertEquals(0, c.score(), "no hard signal should fire; got score " + c.score());
        assertEquals(Tier.LUNA, c.startTier());
    }

    @Test
    void concurrencyRelatedPromptsRegisterAsAHardSignal() {
        // Regression coverage (independent review finding): the HARD_SIGNALS pattern has
        // "concurren" wrapped in \b...\b -- but that requires "concurren" itself to be a
        // COMPLETE word, and it isn't one; real text always continues into "concurrent",
        // "concurrency", or "concurrently", none of which have a word boundary right after
        // "concurren". The pattern could never fire on any real English sentence, silently
        // dropping an entire category of hard-complexity signal (the "race condition"
        // alternative right next to it in the same pattern is a strong hint this was meant
        // to catch concurrency bugs specifically).
        var c = classifier.classify("Fix this concurrency bug in the shared counter.");
        assertTrue(c.score() >= 1, "expected \"concurrency\" to register as a hard signal, got score " + c.score());
    }
}
