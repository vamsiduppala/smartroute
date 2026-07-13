package com.vamsi.smartroute.guardrails;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

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
    public PromptInjectionScanner.Result scan(@RequestBody ScanRequest request) {
        return scanner.scan(request == null || request.text() == null ? "" : request.text());
    }

    @Operation(summary = "Trust a tool's current schema", description = "Fingerprints and records the schema as the trusted baseline for this tool name.")
    @PostMapping("/tools/trust")
    public TrustResponse trust(@RequestBody ToolRequest request) {
        String name = requireName(request);
        String fp = drift.trust(name, schemaOf(request));
        return new TrustResponse(name, fp);
    }

    @Operation(summary = "Check a tool's schema for drift", description = "True if the schema no longer matches the trusted fingerprint (a 'rug pull').")
    @PostMapping("/tools/check")
    public DriftCheckResponse check(@RequestBody ToolRequest request) {
        String name = requireName(request);
        boolean drifted = drift.hasDrifted(name, schemaOf(request));
        return new DriftCheckResponse(name, drifted);
    }

    private static String requireName(ToolRequest request) {
        String name = request == null ? null : request.name();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        return name;
    }

    private static String schemaOf(ToolRequest request) {
        return request.schema() == null ? "" : request.schema();
    }

    public record ScanRequest(String text) {}

    public record ToolRequest(String name, String schema) {}

    public record TrustResponse(String name, String fingerprint) {}

    public record DriftCheckResponse(String name, boolean drifted) {}
}
