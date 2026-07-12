package com.vamsi.smartroute.web;

import com.vamsi.smartroute.routing.RouteResult;
import com.vamsi.smartroute.routing.SmartRouteService;
import com.vamsi.smartroute.routing.Validator;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RouteController {

    private final SmartRouteService router;

    public RouteController(SmartRouteService router) {
        this.router = router;
    }

    /** POST /route  {"prompt": "..."}  ->  which tier answered + token cost. */
    @PostMapping("/route")
    public RouteResult route(@RequestBody RouteRequest request) {
        return router.route(request.prompt(), Validator.nonEmpty());
    }

    public record RouteRequest(String prompt) {}
}
