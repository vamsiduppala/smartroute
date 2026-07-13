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
 * (BUILD_NOTES.md), but until now it was only exercised indirectly through a mocked router
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
        // Per-attempt-cost gotcha (BUILD_NOTES.md): a failed Luna attempt is still billed at
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
}
