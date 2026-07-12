package com.vamsi.smartroute.observability;

import com.vamsi.smartroute.routing.RouteResult;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * Times every {@code SmartRouteService.route(..)} call and records the outcome to telemetry —
 * cross-cutting, so cost/latency are captured no matter which module (rag/memory/governance) drove the call.
 */
@Aspect
@Component
public class RouterTelemetryAspect {

    private final TelemetryService telemetry;

    public RouterTelemetryAspect(TelemetryService telemetry) {
        this.telemetry = telemetry;
    }

    @Around("execution(* com.vamsi.smartroute.routing.SmartRouteService.route(..))")
    public Object aroundRoute(ProceedingJoinPoint pjp) throws Throwable {
        long start = System.nanoTime();
        Object result = pjp.proceed();
        long latencyMs = (System.nanoTime() - start) / 1_000_000;
        if (result instanceof RouteResult r) {
            telemetry.record(r.tierUsed(), r.costUsd(), latencyMs);
        }
        return result;
    }
}
