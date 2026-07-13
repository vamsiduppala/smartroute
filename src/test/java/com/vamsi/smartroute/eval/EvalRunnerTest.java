package com.vamsi.smartroute.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the resilient task-parsing that keeps a benchmark run alive: blank lines are ignored
 * and a single malformed line is skipped rather than aborting the whole (paid) run.
 */
class EvalRunnerTest {

    private final ObjectMapper mapper = new ObjectMapper();

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
}
