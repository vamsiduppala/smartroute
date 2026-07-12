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
}
