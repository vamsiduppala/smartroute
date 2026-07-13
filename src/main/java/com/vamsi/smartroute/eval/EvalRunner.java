package com.vamsi.smartroute.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vamsi.smartroute.model.Tier;
import com.vamsi.smartroute.routing.RouteResult;
import com.vamsi.smartroute.routing.SmartRouteService;
import com.vamsi.smartroute.routing.Validator;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the fixed task set two ways and prints the comparison:
 *   (A) baseline — every task answered by Sol (the "just use the flagship" habit)
 *   (B) routed   — SmartRoute picks the cheapest tier that passes each task's check
 *
 * Activate with:  mvn spring-boot:run -Dspring-boot.run.arguments=--eval
 * Requires OPENAI_API_KEY. Writes a markdown table to eval/results.md.
 * NOTE: numbers are produced HERE, at run time. Nothing in this repo hardcodes results.
 *
 * Robustness: this is a one-shot manual benchmark that makes real, paid model calls, so a
 * failure partway through (a transient API error, one bad task) must not throw away the work
 * already done. A missing/empty task set exits with a clear message; a malformed task line is
 * skipped with a warning instead of aborting parse; and any per-task failure is recorded as an
 * error row and the run continues, so eval/results.md is always written with whatever completed.
 */
@Component
public class EvalRunner implements ApplicationRunner {

    private static final Path TASKS_PATH = Path.of("eval/tasks.jsonl");
    private static final Path RESULTS_PATH = Path.of("eval/results.md");

    private final SmartRouteService router;
    private final OpenAiChatModel chatModel;
    private final ObjectMapper mapper = new ObjectMapper();

    public EvalRunner(SmartRouteService router, OpenAiChatModel chatModel) {
        this.router = router;
        this.chatModel = chatModel;
    }

    public record Task(String id, String prompt, String expect) {}

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!args.containsOption("eval")) return;   // only run when asked

        if (!Files.isRegularFile(TASKS_PATH)) {
            System.err.println("[eval] task set not found at " + TASKS_PATH.toAbsolutePath()
                    + " — nothing to run. Create it with one JSON Task per line, then retry.");
            System.exit(2);
            return;
        }

        List<Task> tasks = parseTasks(Files.readAllLines(TASKS_PATH), mapper);
        if (tasks.isEmpty()) {
            System.err.println("[eval] no valid tasks parsed from " + TASKS_PATH
                    + " — check the file has one JSON object per line.");
            System.exit(2);
            return;
        }

        double baselineCost = 0, routedCost = 0;
        int baselinePass = 0, routedPass = 0, errors = 0;
        int n = tasks.size();
        StringBuilder rows = new StringBuilder();

        for (Task t : tasks) {
            Validator check = ans -> ans != null && ans.toLowerCase().contains(t.expect().toLowerCase());
            try {
                // (A) baseline: force Sol
                var solOpts = OpenAiChatOptions.builder().model(Tier.SOL.modelId).build();
                ChatResponse solResp = chatModel.call(new Prompt(t.prompt(), solOpts));
                String solAns = solResp.getResult().getOutput().getText();
                var u = solResp.getMetadata().getUsage();
                long promptTok = u == null ? 0L : num(u.getPromptTokens());
                long completionTok = u == null ? 0L : num(u.getCompletionTokens());
                double solCost = Tier.SOL.costUsd(promptTok, completionTok);
                boolean solOk = check.accepts(solAns);
                baselineCost += solCost;
                if (solOk) baselinePass++;

                // (B) routed
                RouteResult r = router.route(t.prompt(), check);
                routedCost += r.costUsd();
                if (r.passed()) routedPass++;

                rows.append("| ").append(t.id()).append(" | ").append(r.startTier())
                    .append(" | ").append(r.tierUsed()).append(" | ").append(r.attempts())
                    .append(" | $").append(fmt(solCost)).append(" | $").append(fmt(r.costUsd()))
                    .append(" | ").append(r.passed() ? "✅" : "❌").append(" |\n");
            } catch (Exception e) {
                errors++;
                System.err.println("[eval] task " + t.id() + " failed: " + e
                        + " — skipping, continuing with the rest.");
                rows.append("| ").append(t.id())
                    .append(" | — | — | — | — | — | ⚠️ error |\n");
            }
        }

        double saved = baselineCost == 0 ? 0 : (1 - routedCost / baselineCost) * 100;
        String out = """
                # SmartRoute eval results (generated by EvalRunner — do not hand-edit)

                Tasks: %d  ·  Baseline (always Sol) pass: %d/%d  ·  Routed pass: %d/%d  ·  Errored: %d

                | task | start | ended | attempts | Sol $ | routed $ | routed pass |
                |------|-------|-------|----------|-------|----------|-------------|
                %s
                **Total Sol-only cost:** $%s  ·  **Total routed cost:** $%s  ·  **Saved: %.1f%%**

                _Quality parity matters more than raw savings: only trust the savings number when routed pass ≈ baseline pass._
                """.formatted(n, baselinePass, n, routedPass, n, errors, rows,
                              fmt(baselineCost), fmt(routedCost), saved);

        Files.writeString(RESULTS_PATH, out);
        System.out.println(out);
        if (errors > 0) {
            System.err.println("[eval] completed with " + errors + "/" + n
                    + " task(s) errored — partial results written to " + RESULTS_PATH + ".");
        }
        System.exit(errors == 0 ? 0 : 1); // one-shot benchmark: terminate instead of leaving the web server up
    }

    /**
     * Parse tasks resiliently: blank lines are skipped, and a malformed JSON line is warned about
     * and skipped rather than aborting the whole run. Package-private for unit testing.
     */
    static List<Task> parseTasks(List<String> lines, ObjectMapper mapper) {
        List<Task> tasks = new ArrayList<>();
        int lineNo = 0;
        for (String line : lines) {
            lineNo++;
            if (line.isBlank()) continue;
            try {
                tasks.add(mapper.readValue(line, Task.class));
            } catch (Exception e) {
                System.err.println("[eval] skipping malformed task on line " + lineNo + ": " + e.getMessage());
            }
        }
        return tasks;
    }

    private static long num(Object o) { return o == null ? 0L : ((Number) o).longValue(); }
    private static String fmt(double d) { return String.format("%.5f", d); }
}
