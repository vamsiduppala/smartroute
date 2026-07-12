package com.vamsi.smartroute.guardrails;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/guardrails")
public class GuardrailsController {

    private final PromptInjectionScanner scanner;
    private final ToolDriftDetector drift;

    public GuardrailsController(PromptInjectionScanner scanner, ToolDriftDetector drift) {
        this.scanner = scanner;
        this.drift = drift;
    }

    @PostMapping("/scan")
    public PromptInjectionScanner.Result scan(@RequestBody Map<String, String> body) {
        return scanner.scan(body.getOrDefault("text", ""));
    }

    @PostMapping("/tools/trust")
    public Map<String, String> trust(@RequestBody Map<String, String> body) {
        String fp = drift.trust(body.get("name"), body.getOrDefault("schema", ""));
        return Map.of("name", body.get("name"), "fingerprint", fp);
    }

    @PostMapping("/tools/check")
    public Map<String, Object> check(@RequestBody Map<String, String> body) {
        boolean drifted = drift.hasDrifted(body.get("name"), body.getOrDefault("schema", ""));
        return Map.of("name", body.get("name"), "drifted", drifted);
    }
}
