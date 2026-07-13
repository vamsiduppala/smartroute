package com.vamsi.smartroute.web;

import com.vamsi.smartroute.routing.RouteResult;
import com.vamsi.smartroute.routing.SmartRouteService;
import com.vamsi.smartroute.routing.Validator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "routing", description = "Core tier router — Luna/Terra/Sol escalation, no gateway wrapping")
public class RouteController {

    private final SmartRouteService router;

    public RouteController(SmartRouteService router) {
        this.router = router;
    }

    @Operation(summary = "Route a prompt", description = "Classifies the prompt to a starting tier, then escalates Luna -> Terra -> Sol until a validator accepts the answer.")
    @PostMapping("/route")
    public RouteResult route(@RequestBody RouteRequest request) {
        if (request == null || request.prompt() == null || request.prompt().isBlank()) {
            throw new IllegalArgumentException("prompt must not be blank");
        }
        return router.route(request.prompt(), Validator.nonEmpty());
    }

    public record RouteRequest(String prompt) {}
}
