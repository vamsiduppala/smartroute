package com.vamsi.smartroute.observability;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/observability")
public class ObservabilityController {

    private final TelemetryService telemetry;

    public ObservabilityController(TelemetryService telemetry) {
        this.telemetry = telemetry;
    }

    /** Quick human-readable rollup; full metrics are also exposed via Actuator/Micrometer. */
    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        return Map.of(
                "totalCalls", telemetry.totalCalls(),
                "totalCostUsd", telemetry.totalCostUsd(),
                "callsByTier", telemetry.callsByTier()
        );
    }
}
