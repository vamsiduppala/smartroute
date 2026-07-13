package com.vamsi.smartroute.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vamsi.smartroute.model.Tier;
import com.vamsi.smartroute.routing.RouteResult;
import com.vamsi.smartroute.routing.SmartRouteService;
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
import static org.mockito.Mockito.*;

/**
 * Covers the two pieces of EvalRunner resilience that keep a paid benchmark run alive:
 * (1) parseTasks tolerates blank/malformed lines instead of aborting the parse, and
 * (2) runBenchmark records a failing task as an error row and keeps going instead of letting
 * one exception kill the whole run and lose all partial results.
 */
class EvalRunnerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Build a real ChatResponse rather than mocking its final accessors — mirrors
     * SmartRouteServiceTest.responseOf and stays robust if the inline mock-maker is dropped.
     */
    private static ChatResponse responseOf(String answer, int promptTokens, int completionTokens) {
        AssistantMessage message = new AssistantMessage(answer);
        Generation generation = new Generation(message);
        DefaultUsage usage = new DefaultUsage(promptTokens, completionTokens);
        ChatResponseMetadata metadata = ChatResponseMetadata.builder().usage(usage).build();
        return new ChatResponse(List.of(generation), metadata);
    }

    @Test
    void parsesWellFormedTasksAndSkipsBlankLines() {
        List<String> lines = List.of(
                "{\"id\":\"t1\",\"prompt\":\"2+2?\",\"expect\":\"4\"}",
                "",
                "   ",
                "{\"id\":\"t2\",\"prompt\":\"capital of France?\",\"expect\":\"paris\"}");

        List<EvalRunner.Task> tasks = EvalRunner.parseTasks(lines, mapper);

        assertEquals(2, tasks.size(), "blank/whitespace lines must not produce tasks");
        assertEquals("t1", tasks.get(0).id());
        assertEquals("4", tasks.get(0).expect());
        assertEquals("t2", tasks.get(1).id());
    }

    @Test
    void skipsMalformedLineButKeepsTheRest() {
        List<String> lines = List.of(
                "{\"id\":\"t1\",\"prompt\":\"ok\",\"expect\":\"a\"}",
                "{ this is not valid json",
                "{\"id\":\"t3\",\"prompt\":\"still ok\",\"expect\":\"c\"}");

        List<EvalRunner.Task> tasks = EvalRunner.parseTasks(lines, mapper);

        assertEquals(2, tasks.size(), "a malformed line must be dropped, not abort the run");
        assertEquals("t1", tasks.get(0).id());
        assertEquals("t3", tasks.get(1).id(), "parsing must continue past the bad line");
    }

    @Test
    void emptyInputYieldsNoTasks() {
        assertTrue(EvalRunner.parseTasks(List.of(), mapper).isEmpty());
        assertTrue(EvalRunner.parseTasks(List.of("", "  "), mapper).isEmpty());
    }

    @Test
    void aFailingTaskIsRecordedAndTheRunContinues() {
        OpenAiChatModel chatModel = mock(OpenAiChatModel.class);
        SmartRouteService router = mock(SmartRouteService.class);
        // Simulate an API outage on every baseline call.
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("simulated API outage"));

        EvalRunner runner = new EvalRunner(router, chatModel);
        List<EvalRunner.Task> tasks = List.of(
                new EvalRunner.Task("t1", "p1", "a"),
                new EvalRunner.Task("t2", "p2", "b"));

        EvalRunner.EvalReport report = assertDoesNotThrow(() -> runner.runBenchmark(tasks),
                "one task throwing must not propagate out of the run");

        assertEquals(2, report.tasks());
        assertEquals(2, report.errored(), "both tasks failed, so both must count as errored");
        assertTrue(report.markdown().contains("Errored: 2"));
        assertTrue(report.markdown().contains("t1") && report.markdown().contains("t2"),
                "both tasks must appear in the report despite the first one failing");
        assertTrue(report.markdown().contains("⚠️ error"), "failed tasks render as error rows");
        // The baseline call was attempted for BOTH tasks — the loop did not abort on the first failure.
        verify(chatModel, times(2)).call(any(Prompt.class));
        // A baseline failure short-circuits before routing, so the router is never reached.
        verify(router, never()).route(any(), any());
    }

    @Test
    void aggregatesPassCountsAcrossAMixedSuccessAndErrorRun() {
        OpenAiChatModel chatModel = mock(OpenAiChatModel.class);
        SmartRouteService router = mock(SmartRouteService.class);

        // Baseline (Sol) answers "Paris" with a small token usage for every call. Build the real
        // response object (final accessors can't be stubbed without the inline mock-maker, which
        // BUILD_NOTES flags as deprecated on future JDKs) — same pattern as SmartRouteServiceTest.
        when(chatModel.call(any(Prompt.class))).thenReturn(responseOf("Paris", 100, 50));

        // Routing succeeds for the good task and throws for the bad one.
        RouteResult routed = new RouteResult("Paris", Tier.LUNA, Tier.LUNA, 1, 100, 50, 0.0004, true, "simple");
        when(router.route(eq("good"), any())).thenReturn(routed);
        when(router.route(eq("bad"), any())).thenThrow(new RuntimeException("router blew up"));

        EvalRunner runner = new EvalRunner(router, chatModel);
        List<EvalRunner.Task> tasks = List.of(
                new EvalRunner.Task("good", "good", "paris"),   // baseline + routed both pass
                new EvalRunner.Task("bad", "bad", "paris"));    // baseline passes, routing errors

        EvalRunner.EvalReport report = runner.runBenchmark(tasks);
        String md = report.markdown();

        assertEquals(2, report.tasks());
        assertEquals(1, report.errored());
        // Both baselines answered "Paris" which matches expect "paris" (case-insensitive) -> 2/2.
        assertTrue(md.contains("Baseline (always Sol) pass: 2/2"), md);
        // Only the good task made it through routing -> 1/2.
        assertTrue(md.contains("Routed pass: 1/2"), md);
        assertTrue(md.contains("Errored: 1"), md);
        assertTrue(md.contains("✅"), "the good task renders as a pass");
        assertTrue(md.contains("⚠️ error"), "the bad task renders as an error row");
    }
}
