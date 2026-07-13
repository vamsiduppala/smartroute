package com.vamsi.smartroute.observability;

import com.vamsi.smartroute.model.Tier;
import com.vamsi.smartroute.routing.ComplexityClassifier;
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
}
