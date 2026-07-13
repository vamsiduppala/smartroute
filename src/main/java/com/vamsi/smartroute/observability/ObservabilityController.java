package com.vamsi.smartroute.observability;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/observability")
@Tag(name = "observability", description = "Cost, latency, and per-tier call telemetry")
public class ObservabilityController {

    private final TelemetryService telemetry;

    public ObservabilityController(TelemetryService telemetry) {
        this.telemetry = telemetry;
    }

    /** Quick human-readable rollup; full metrics are also exposed via Actuator/Micrometer. */
    @Operation(summary = "Get a quick metrics rollup", description = "Total calls, total cost, and calls-by-tier. Full Micrometer metrics are also exposed via Actuator.")
    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        return Map.of(
                "totalCalls", telemetry.totalCalls(),
                "totalCostUsd", telemetry.totalCostUsd(),
                "callsByTier", telemetry.callsByTier()
        );
    }
}
