package com.vamsi.smartroute.gateway;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@Tag(name = "gateway", description = "Full gateway pass: guardrails -> governance -> routing -> spend booking, in one call")
public class GatewayController {

    private final GatewayService gateway;

    public GatewayController(GatewayService gateway) {
        this.gateway = gateway;
    }

    @Operation(summary = "Route through the full gateway", description = "Scans for prompt injection, checks the tenant's budget, routes across GPT-5.6 tiers, then books the actual spend.")
    @PostMapping("/gateway/route")
    public GatewayResult route(@RequestBody Map<String, String> body) {
        String prompt = body.getOrDefault("prompt", "");
        if (prompt.isBlank()) {
            throw new IllegalArgumentException("prompt must not be blank");
        }
        return gateway.handle(body.getOrDefault("tenant", "default"), prompt);
    }
}
