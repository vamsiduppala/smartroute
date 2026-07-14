# observability module — build notes

**Rides:** AI SDK 7 telemetry redesign / `@ai-sdk/otel` (2026-07-09) — same idea, Spring/Micrometer implementation.

**What it does:** `RouterTelemetryAspect` is an `@Around` aspect on `SmartRouteService.route(..)`/`routeFrom(..)` that records tier, cost (USD), latency, and call counts to Micrometer via `TelemetryService` — ONE entry per real model call (`AttemptRecord`, see `routing-NOTES.md`), not one per outer request. `GET /observability/metrics` returns a JSON rollup; the same metrics are also exposed in Prometheus text format at `/actuator/prometheus` (`smartroute_calls_total`, `smartroute_cost_usd_total`, `smartroute_latency_seconds{,_max}`, each tagged `tier=`), ready to scrape into Prometheus/Grafana.

**Why an aspect:** telemetry stays cross-cutting — cost/latency are captured no matter which module (rag, memory, governance) originated the model call, without each module wiring its own metrics.

**Consuming the metrics (PromQL against the scraped series):**
```promql
# Spend split by tier
sum by (tier) (smartroute_cost_usd_total)

# Call volume by tier, per second
sum by (tier) (rate(smartroute_calls_total[5m]))

# Average latency by tier
  sum by (tier) (rate(smartroute_latency_seconds_sum[5m]))
/ sum by (tier) (rate(smartroute_latency_seconds_count[5m]))

# The routing win, live: fraction of calls the cheapest tier (Luna) served
  sum(smartroute_calls_total{tier="LUNA"}) / sum(smartroute_calls_total)
```
The last query is the whole thesis as a production signal — the higher it trends, the more traffic the cheap heuristic is handling without escalation.

**Deps added:** `spring-boot-starter-actuator` (Micrometer) + `spring-boot-starter-aop` + `micrometer-registry-prometheus` (the scrape endpoint; exposed via `management.endpoints.web.exposure.include: health,info,prometheus`).
**Tests:** aggregation of calls + cost across tiers, empty-start — using an in-memory `SimpleMeterRegistry`, no live calls. `RouterTelemetryAspectTest` separately verifies the `@Around` advice itself actually fires, via a real `AspectJProxyFactory` proxy — not just that `TelemetryService` works as a POJO.

**Gotcha #1 (resolved 2026-07-12):** the pointcut was `execution(* ...SmartRouteService.route(..))` — name-exact, so it silently missed `routeFrom(..)` once that method existed (added for the governance DOWNGRADE fix, see `gateway-NOTES.md`). Any DOWNGRADE-routed request vanished from telemetry with no error. Fixed by widening to `route*(..)`; caught by `RouterTelemetryAspectTest`, which fails without the fix.

**Gotcha #2 (resolved 2026-07-13):** the aspect only recorded telemetry on a successful return — a model call that failed partway through escalation (Luna succeeds, Terra's call itself throws) meant `pjp.proceed()` threw before the `telemetry.record(...)` line, so Luna's real, already-incurred cost never reached telemetry at all. The aspect now also catches `PartialRouteException` (see `routing-NOTES.md`) and records whatever attempts succeeded before the failure. See `RouterTelemetryAspectTest.recordsPartialCostWhenAModelCallFailsMidEscalation`.

**Gotcha #3 (resolved 2026-07-13, same day, a deeper fix than #1/#2):** even after #1 and #2, the aspect still wrapped the WHOLE escalation as a single join point — a 3-tier escalation (Luna, Terra, Sol all actually called) was recorded as ONE telemetry entry attributed entirely to Sol, undercounting real API call volume and misattributing Luna/Terra's cost and latency. `totalCostUsd` happened to stay accurate (it's a straight sum), but `callsByTier()` and `totalCalls()` did not. Real fix, not a patch: `RouteResult` now carries `List<AttemptRecord>` (tier + tokens + cost + **per-attempt latency, measured directly in `SmartRouteService`** — more precise than this aspect's old whole-method wall-clock timing, which also counted classifier overhead); the aspect just iterates and records each one. See `RouterTelemetryAspectTest.multiAttemptEscalationRecordsOneTelemetryEntryPerRealModelCall` — confirmed meaningful by reverting to aggregate-only recording and watching it fail (expected 3 entries, got 1).
