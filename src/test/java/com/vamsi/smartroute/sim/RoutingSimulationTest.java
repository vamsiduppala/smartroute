package com.vamsi.smartroute.sim;

import com.vamsi.smartroute.model.Tier;
import com.vamsi.smartroute.routing.ComplexityClassifier;
import com.vamsi.smartroute.routing.RouteResult;
import com.vamsi.smartroute.routing.SmartRouteService;
import com.vamsi.smartroute.routing.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * SIMULATION — NOT a live API benchmark. A deterministic stub stands in for GPT-5.6: each tier
 * "answers" a task only if it is at least the tier that task requires; otherwise the router escalates.
 * Costs use the REAL published GPT-5.6 pricing via {@link Tier#costUsd}. This demonstrates + asserts
 * routing economics with ZERO credits. Real API numbers would require an OpenAI account with billing.
 */
class RoutingSimulationTest {

    private record Task(String prompt, String expect, Tier required) {}

    private static final List<Task> TASKS = List.of(
            new Task("What is the capital of France?", "paris", Tier.LUNA),
            new Task("Return only the value of 2+2.", "4", Tier.LUNA),
            new Task("Explain the trade-off and the algorithm here.", "log", Tier.TERRA),
            new Task("Optimize this algorithm and prove its complexity: ```code```", "atomic", Tier.SOL),
            new Task("What does HTTP status 404 mean?", "not found", Tier.LUNA)
    );

    private static Tier tierOf(String modelId) {
        if (modelId.contains("luna")) return Tier.LUNA;
        if (modelId.contains("terra")) return Tier.TERRA;
        return Tier.SOL;
    }

    private OpenAiChatModel simulatedModel() {
        Map<String, Task> byPrompt = new LinkedHashMap<>();
        TASKS.forEach(t -> byPrompt.put(t.prompt(), t));
        OpenAiChatModel model = mock(OpenAiChatModel.class);
        when(model.call(any(Prompt.class))).thenAnswer(inv -> {
            Prompt p = inv.getArgument(0);
            String text = p.getInstructions().get(0).getText();
            Tier tier = tierOf(((OpenAiChatOptions) p.getOptions()).getModel());
            Task task = byPrompt.get(text);
            boolean ok = task != null && tier.ordinal() >= task.required().ordinal();
            String out = ok ? ("Answer: " + task.expect()) : "I don't know.";
            int inTok = Math.max(1, text.length() / 4);
            int outTok = Math.max(1, out.length() / 4);
            ChatResponseMetadata meta = ChatResponseMetadata.builder()
                    .usage(new DefaultUsage(inTok, outTok)).build();
            return new ChatResponse(List.of(new Generation(new AssistantMessage(out))), meta);
        });
        return model;
    }

    @Test
    void routingCostsNoMoreThanAlwaysSolAtEqualQuality() throws IOException {
        OpenAiChatModel model = simulatedModel();
        SmartRouteService router = new SmartRouteService(model, new ComplexityClassifier());

        double baseline = 0, routed = 0;
        int baselinePass = 0, routedPass = 0;
        StringBuilder rows = new StringBuilder();

        for (Task t : TASKS) {
            Validator check = ans -> ans.toLowerCase().contains(t.expect().toLowerCase());

            ChatResponse sol = model.call(new Prompt(t.prompt(),
                    OpenAiChatOptions.builder().model(Tier.SOL.modelId).build()));
            String solAns = sol.getResult().getOutput().getText();
            var u = sol.getMetadata().getUsage();
            double solCost = Tier.SOL.costUsd(u.getPromptTokens(), u.getCompletionTokens());
            baseline += solCost;
            if (check.accepts(solAns)) baselinePass++;

            RouteResult r = router.route(t.prompt(), check);
            routed += r.costUsd();
            if (r.passed()) routedPass++;

            rows.append("| ").append(t.required()).append(" | ").append(r.tierUsed())
                    .append(" | ").append(r.attempts())
                    .append(" | $").append(String.format("%.6f", solCost))
                    .append(" | $").append(String.format("%.6f", r.costUsd()))
                    .append(" | ").append(r.passed() ? "PASS" : "FAIL").append(" |\n");
        }

        assertEquals(TASKS.size(), routedPass, "routing must keep quality parity (all pass)");
        assertEquals(baselinePass, routedPass, "same pass rate as always-Sol");
        assertTrue(routed <= baseline, "routing must not cost more in aggregate");

        double saved = baseline == 0 ? 0 : (1 - routed / baseline) * 100;
        String md = """
                # SmartRoute routing simulation (NOT a live API benchmark)

                Deterministic stub model + REAL published GPT-5.6 pricing (`Tier.costUsd`). Zero credits used.
                Live API numbers would require an OpenAI account with billing enabled.

                | task requires | routed ended at | attempts | Sol-only $ | routed $ | pass |
                |---------------|-----------------|----------|-----------|----------|------|
                %s
                **Always-Sol:** $%.6f  ·  **Routed:** $%.6f  ·  **Saved: %.1f%%** at equal pass rate (%d/%d).
                """.formatted(rows, baseline, routed, saved, routedPass, TASKS.size());
        Files.writeString(Path.of("docs/simulation-results.md"), md);
    }
}
