package com.vamsi.smartroute.guardrails;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/guardrails")
@Tag(name = "guardrails", description = "Prompt-injection scanning and MCP tool-schema drift detection")
public class GuardrailsController {

    private final PromptInjectionScanner scanner;
    private final ToolDriftDetector drift;

    public GuardrailsController(PromptInjectionScanner scanner, ToolDriftDetector drift) {
        this.scanner = scanner;
        this.drift = drift;
    }

    @Operation(summary = "Scan text for known prompt-injection patterns")
    @PostMapping("/scan")
    public PromptInjectionScanner.Result scan(@RequestBody Map<String, String> body) {
        return scanner.scan(body.getOrDefault("text", ""));
    }

    @Operation(summary = "Trust a tool's current schema", description = "Fingerprints and records the schema as the trusted baseline for this tool name.")
    @PostMapping("/tools/trust")
    public Map<String, String> trust(@RequestBody Map<String, String> body) {
        String name = requireName(body);
        String fp = drift.trust(name, body.getOrDefault("schema", ""));
        return Map.of("name", name, "fingerprint", fp);
    }

    @Operation(summary = "Check a tool's schema for drift", description = "True if the schema no longer matches the trusted fingerprint (a 'rug pull').")
    @PostMapping("/tools/check")
    public Map<String, Object> check(@RequestBody Map<String, String> body) {
        String name = requireName(body);
        boolean drifted = drift.hasDrifted(name, body.getOrDefault("schema", ""));
        return Map.of("name", name, "drifted", drifted);
    }

    private static String requireName(Map<String, String> body) {
        String name = body.get("name");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        return name;
    }
}
