package com.vamsi.smartroute.observability;

import com.vamsi.smartroute.routing.AttemptRecord;
import com.vamsi.smartroute.routing.PartialRouteException;
import com.vamsi.smartroute.routing.RouteResult;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * Records every real model call made during {@code SmartRouteService.route(..)}/
 * {@code routeFrom(..)} to telemetry — cross-cutting, so cost/latency are captured no matter
 * which module (rag/memory/governance) drove the call, or whether it went through the
 * classifier-driven {@code route} or the forced-tier {@code routeFrom} (e.g. a BudgetGuard
 * DOWNGRADE).
 *
 * Records ONE telemetry entry per {@link AttemptRecord} in the result, not one per outer
 * route()/routeFrom() call — an escalation that makes 3 real API calls (Luna, Terra, Sol) used
 * to be recorded as a single call attributed entirely to the final tier, undercounting real API
 * usage and misattributing cost/latency away from Luna and Terra. Latency is measured
 * per-attempt directly in SmartRouteService now (bracketing just the model call, more precise
 * than this aspect's old whole-method wall-clock timing, which also included classifier
 * overhead), so this aspect no longer measures time itself at all.
 */
@Aspect
@Component
public class RouterTelemetryAspect {

    private final TelemetryService telemetry;

    public RouterTelemetryAspect(TelemetryService telemetry) {
        this.telemetry = telemetry;
    }

    // "route*" -- NOT just "route" -- so this also catches routeFrom(..). A name-exact pointcut
    // here previously missed routeFrom entirely, silently dropping telemetry for DOWNGRADE calls.
    @Around("execution(* com.vamsi.smartroute.routing.SmartRouteService.route*(..))")
    public Object aroundRoute(ProceedingJoinPoint pjp) throws Throwable {
        try {
            Object result = pjp.proceed();
            if (result instanceof RouteResult r) {
                recordEach(r.attemptRecords());
            }
            return result;
        } catch (PartialRouteException partial) {
            // Attempts before the failure (if any) already succeeded and are in the partial
            // result's attemptRecords -- record them before letting the failure propagate.
            recordEach(partial.partialResult().attemptRecords());
            throw partial;
        }
    }

    private void recordEach(Iterable<AttemptRecord> attempts) {
        for (AttemptRecord a : attempts) {
            telemetry.record(a.tier(), a.costUsd(), a.latencyMs());
        }
    }
}
