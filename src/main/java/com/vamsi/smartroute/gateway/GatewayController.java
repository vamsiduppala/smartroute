package com.vamsi.smartroute.gateway;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class GatewayController {

    private final GatewayService gateway;

    public GatewayController(GatewayService gateway) {
        this.gateway = gateway;
    }

    /** POST /gateway/route  {"tenant":"acme","prompt":"..."}  -> guardrails + budget + routing in one pass. */
    @PostMapping("/gateway/route")
    public GatewayResult route(@RequestBody Map<String, String> body) {
        return gateway.handle(body.getOrDefault("tenant", "default"), body.getOrDefault("prompt", ""));
    }
}
