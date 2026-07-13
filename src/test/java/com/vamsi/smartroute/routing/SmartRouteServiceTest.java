package com.vamsi.smartroute.routing;

import com.vamsi.smartroute.model.Tier;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Direct coverage of the escalation algorithm -- this is the whole thesis of the project
 * (see docs/ENGINEERING.md), but until now it was only exercised indirectly through a mocked router
 * in GatewayServiceTest. Everything here is offline: OpenAiChatModel is mocked, no live call.
 */
class SmartRouteServiceTest {

    private final ComplexityClassifier classifier = new ComplexityClassifier();

    /**
     * ChatResponse/Generation/AssistantMessage carry final accessor methods (getText(),
     * getOutput(), ...) that Mockito can't stub -- so build the real, plain-data objects
     * instead of mocking the response chain. Only OpenAiChatModel itself gets mocked.
     */
    private static ChatResponse responseOf(String answer, long promptTokens, long completionTokens) {
        AssistantMessage message = new AssistantMessage(answer);
        Generation generation = new Generation(message);
        DefaultUsage usage = new DefaultUsage((int) promptTokens, (int) completionTokens);
        ChatResponseMetadata metadata = ChatResponseMetadata.builder().usage(usage).build();
        return new ChatResponse(List.of(generation), metadata);
    }

    @Test
    void passesAtStartingTierWithoutEscalating() {
        OpenAiChatModel chatModel = mock(OpenAiChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(responseOf("Paris", 10, 5));
        SmartRouteService router = new SmartRouteService(chatModel, classifier);

        RouteResult r = router.route("What is the capital of France?", Validator.nonEmpty());

        assertEquals(Tier.LUNA, r.startTier());   // short, simple prompt -> classifier starts at Luna
        assertEquals(Tier.LUNA, r.tierUsed());
        assertEquals(1, r.attempts());
        assertTrue(r.passed());
        assertEquals("Paris", r.answer());
        assertEquals(Tier.LUNA.costUsd(10, 5), r.costUsd(), 1e-12);
    }

    @Test
    void escalatesOneTierWhenFirstAnswerFailsValidation() {
        OpenAiChatModel chatModel = mock(OpenAiChatModel.class);
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(responseOf("I cannot help with that", 10, 5))   // Luna: rejected
                .thenReturn(responseOf("42", 10, 8));                       // Terra: accepted
        SmartRouteService router = new SmartRouteService(chatModel, classifier);

        RouteResult r = router.route("What is 6 times 7?", Validator.nonEmpty());

        assertEquals(Tier.LUNA, r.startTier());
        assertEquals(Tier.TERRA, r.tierUsed());
        assertEquals(2, r.attempts());
        assertTrue(r.passed());
        assertEquals("42", r.answer());
        // Per-attempt-cost gotcha (see docs/ENGINEERING.md): a failed Luna attempt is still billed at
        // Luna's rate, ON TOP OF the Terra attempt that succeeds -- not just the final tier's rate.
        double expected = Tier.LUNA.costUsd(10, 5) + Tier.TERRA.costUsd(10, 8);
        assertEquals(expected, r.costUsd(), 1e-12);
    }

    @Test
    void stopsClimbingAfterSolFailsInsteadOfLoopingForever() {
        OpenAiChatModel chatModel = mock(OpenAiChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(responseOf("", 10, 0));   // always rejected
        SmartRouteService router = new SmartRouteService(chatModel, classifier);

        RouteResult r = router.route("Summarize tomorrow's weather forecast.", Validator.nonEmpty());

        assertEquals(Tier.LUNA, r.startTier());  // plain, short prompt -> classifier starts at Luna
        assertEquals(Tier.SOL, r.tierUsed());    // climbed all the way up
        assertEquals(3, r.attempts());           // Luna -> Terra -> Sol, then stop (escalate() returns null)
        assertFalse(r.passed());
    }

    @Test
    void routeFromForcesTheGivenStartTierOverTheClassifier() {
        String prompt = "Architect and derive a proof for an optimized recursive algorithm, step by step.";
        // Confirm the premise: left to itself, the classifier would NOT start this at Luna
        // (hard-complexity signals: "architect", "derive", "algorithm", "step by step").
        assertNotEquals(Tier.LUNA, classifier.classify(prompt).startTier());

        OpenAiChatModel chatModel = mock(OpenAiChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(responseOf("42", 10, 8));
        SmartRouteService router = new SmartRouteService(chatModel, classifier);

        // routeFrom must override the classifier and force Luna anyway -- this is what
        // GatewayService uses to actually carry out a BudgetGuard DOWNGRADE decision,
        // instead of just labeling the response and routing normally regardless.
        RouteResult r = router.routeFrom(prompt, Validator.nonEmpty(), Tier.LUNA);

        assertEquals(Tier.LUNA, r.startTier());
        assertEquals(Tier.LUNA, r.tierUsed());
        assertEquals(1, r.attempts());
    }

    @Test
    void modelFailureAfterAnEarlierSuccessPreservesThatCostInAPartialRouteException() {
        // Luna succeeds (rejected -> escalates, real cost incurred) then Terra's call itself
        // throws (network error, upstream 5xx, etc.) -- before this fix, that exception would
        // propagate bare and Luna's already-real cost would be lost entirely: never booked to
        // the ledger, never recorded by telemetry, even though the tokens were genuinely spent.
        OpenAiChatModel chatModel = mock(OpenAiChatModel.class);
        RuntimeException upstreamFailure = new RuntimeException("simulated upstream 503");
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(responseOf("I cannot help with that", 10, 5))   // Luna: rejected, real cost
                .thenThrow(upstreamFailure);                                // Terra: the call itself fails
        SmartRouteService router = new SmartRouteService(chatModel, classifier);

        PartialRouteException thrown = assertThrows(PartialRouteException.class,
                () -> router.route("What is 6 times 7?", Validator.nonEmpty()));

        assertSame(upstreamFailure, thrown.getCause());
        RouteResult partial = thrown.partialResult();
        assertEquals(2, partial.attempts());               // Luna (counted) + the failed Terra attempt
        assertEquals(Tier.TERRA, partial.tierUsed());       // the tier that was in progress when it failed
        assertFalse(partial.passed());
        // Only Luna's cost -- the failed Terra attempt contributed zero tokens, no response came back.
        assertEquals(Tier.LUNA.costUsd(10, 5), partial.costUsd(), 1e-12);
    }

    @Test
    void modelFailureOnTheFirstAttemptStillReportsZeroPartialCost() {
        OpenAiChatModel chatModel = mock(OpenAiChatModel.class);
        RuntimeException upstreamFailure = new RuntimeException("simulated upstream 503");
        when(chatModel.call(any(Prompt.class))).thenThrow(upstreamFailure);
        SmartRouteService router = new SmartRouteService(chatModel, classifier);

        PartialRouteException thrown = assertThrows(PartialRouteException.class,
                () -> router.route("What is the capital of France?", Validator.nonEmpty()));

        assertEquals(1, thrown.partialResult().attempts());
        assertEquals(0.0, thrown.partialResult().costUsd(), 1e-12);
    }

    @Test
    void malformedResponseAfterAnEarlierSuccessAlsoPreservesThatCost() {
        // Regression coverage (independent review finding): the call itself can SUCCEED but
        // return a malformed response (no generations) that throws while being PARSED --
        // response.getResult().getOutput().getText(). An earlier version's try/catch only
        // wrapped chatModel.call(...) itself, so a parsing failure here propagated as a bare
        // exception and Luna's already-real cost would have been lost, same as if the call had
        // thrown outright.
        OpenAiChatModel chatModel = mock(OpenAiChatModel.class);
        ChatResponse malformed = new ChatResponse(List.of());   // no generations -> getResult() throws
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(responseOf("I cannot help with that", 10, 5))   // Luna: rejected, real cost
                .thenReturn(malformed);                                     // Terra: "succeeds" but is malformed
        SmartRouteService router = new SmartRouteService(chatModel, classifier);

        PartialRouteException thrown = assertThrows(PartialRouteException.class,
                () -> router.route("What is 6 times 7?", Validator.nonEmpty()));

        assertEquals(Tier.LUNA.costUsd(10, 5), thrown.partialResult().costUsd(), 1e-12);
        assertEquals(1, thrown.partialResult().attemptRecords().size());   // only Luna's real attempt
    }
}
