package com.vamsi.smartroute.observability;

import com.vamsi.smartroute.model.Tier;
import com.vamsi.smartroute.routing.ComplexityClassifier;
import com.vamsi.smartroute.routing.PartialRouteException;
import com.vamsi.smartroute.routing.SmartRouteService;
import com.vamsi.smartroute.routing.Validator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the aspect actually fires as AOP advice -- TelemetryServiceTest only covers the
 * plain POJO. Builds a real AspectJ proxy around SmartRouteService, the same mechanism Spring
 * uses at runtime, so a pointcut typo (e.g. matching "route" but not "routeFrom") would be caught.
 */
class RouterTelemetryAspectTest {

    private static ChatResponse responseOf(String answer, long promptTokens, long completionTokens) {
        Generation generation = new Generation(new AssistantMessage(answer));
        DefaultUsage usage = new DefaultUsage((int) promptTokens, (int) completionTokens);
        ChatResponseMetadata metadata = ChatResponseMetadata.builder().usage(usage).build();
        return new ChatResponse(List.of(generation), metadata);
    }

    private static SmartRouteService proxiedRouter(TelemetryService telemetry, OpenAiChatModel chatModel) {
        SmartRouteService real = new SmartRouteService(chatModel, new ComplexityClassifier());
        AspectJProxyFactory factory = new AspectJProxyFactory(real);
        factory.addAspect(new RouterTelemetryAspect(telemetry));
        return factory.getProxy();
    }

    @Test
    void recordsTelemetryForRoute() {
        TelemetryService telemetry = new TelemetryService(new SimpleMeterRegistry());
        OpenAiChatModel chatModel = mock(OpenAiChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(responseOf("Paris", 10, 5));
        SmartRouteService router = proxiedRouter(telemetry, chatModel);

        router.route("What is the capital of France?", Validator.nonEmpty());

        assertEquals(1, telemetry.totalCalls());
        assertEquals(Tier.LUNA.costUsd(10, 5), telemetry.totalCostUsd(), 1e-12);
    }

    @Test
    void recordsTelemetryForRouteFromToo() {
        // Regression coverage: a name-exact pointcut ("route" only) would silently miss this --
        // routeFrom is what GatewayService calls on a BudgetGuard DOWNGRADE.
        TelemetryService telemetry = new TelemetryService(new SimpleMeterRegistry());
        OpenAiChatModel chatModel = mock(OpenAiChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(responseOf("42", 10, 8));
        SmartRouteService router = proxiedRouter(telemetry, chatModel);

        router.routeFrom("some prompt", Validator.nonEmpty(), Tier.LUNA);

        assertEquals(1, telemetry.totalCalls());
        assertEquals(Tier.LUNA.costUsd(10, 8), telemetry.totalCostUsd(), 1e-12);
    }

    @Test
    void recordsPartialCostWhenAModelCallFailsMidEscalation() {
        // Regression coverage: before PartialRouteException existed, a mid-escalation failure
        // propagated as a bare exception and this aspect's pjp.proceed() call would throw
        // before ever reaching the telemetry.record(...) line -- Luna's real cost here would
        // have been silently dropped from telemetry entirely.
        TelemetryService telemetry = new TelemetryService(new SimpleMeterRegistry());
        OpenAiChatModel chatModel = mock(OpenAiChatModel.class);
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(responseOf("I cannot help with that", 10, 5))   // Luna: rejected, real cost
                .thenThrow(new RuntimeException("simulated upstream 503"));  // Terra: call itself fails
        SmartRouteService router = proxiedRouter(telemetry, chatModel);

        assertThrows(PartialRouteException.class,
                () -> router.route("What is 6 times 7?", Validator.nonEmpty()));

        assertEquals(1, telemetry.totalCalls());
        assertEquals(Tier.LUNA.costUsd(10, 5), telemetry.totalCostUsd(), 1e-12);
    }

    @Test
    void multiAttemptEscalationRecordsOneTelemetryEntryPerRealModelCall() {
        // The actual granularity fix: before, the aspect wrapped the WHOLE escalation as one
        // join point, so 3 real model calls (Luna, Terra, Sol) got recorded as a single
        // telemetry entry attributed entirely to Sol -- undercounting real API call volume and
        // misattributing Luna/Terra's cost. Now each AttemptRecord in the result gets its own
        // telemetry.record(...) call.
        TelemetryService telemetry = new TelemetryService(new SimpleMeterRegistry());
        OpenAiChatModel chatModel = mock(OpenAiChatModel.class);
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(responseOf("I cannot help with that", 10, 5))    // Luna: rejected
                .thenReturn(responseOf("I cannot help with that", 12, 6))    // Terra: rejected
                .thenReturn(responseOf("42", 14, 7));                        // Sol: accepted
        SmartRouteService router = proxiedRouter(telemetry, chatModel);

        router.route("What is 6 times 7?", Validator.nonEmpty());

        assertEquals(3, telemetry.totalCalls());   // one per real attempt, not one for the whole request
        assertEquals(1L, telemetry.callsByTier().get("LUNA"));
        assertEquals(1L, telemetry.callsByTier().get("TERRA"));
        assertEquals(1L, telemetry.callsByTier().get("SOL"));
        double expectedTotal = Tier.LUNA.costUsd(10, 5) + Tier.TERRA.costUsd(12, 6) + Tier.SOL.costUsd(14, 7);
        assertEquals(expectedTotal, telemetry.totalCostUsd(), 1e-12);
    }
}
