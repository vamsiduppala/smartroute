package com.vamsi.smartroute.guardrails;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Heuristic prompt-injection scanner: flags common override/exfiltration patterns before a prompt reaches a model. */
@Component
public class PromptInjectionScanner {

    public record Result(boolean flagged, int score, List<String> matched) {}

    private static final Pattern[] SIGNS = {
            Pattern.compile("(?i)ignore (all|any|the)?\\s*(previous|prior|above) (instructions|prompts?)"),
            Pattern.compile("(?i)disregard (the )?(system|previous) (prompt|instructions)"),
            Pattern.compile("(?i)you are now (a|an|in) "),
            Pattern.compile("(?i)reveal (your )?(system prompt|instructions|hidden)"),
            Pattern.compile("(?i)(print|show|leak) (the )?(api ?key|secret|password|token)"),
            Pattern.compile("(?i)developer mode|jailbreak|DAN mode"),
    };

    public Result scan(String text) {
        if (text == null || text.isBlank()) return new Result(false, 0, List.of());
        List<String> hits = new ArrayList<>();
        for (Pattern p : SIGNS) {
            var m = p.matcher(text);
            if (m.find()) hits.add(m.group());
        }
        return new Result(!hits.isEmpty(), hits.size(), hits);
    }
}
