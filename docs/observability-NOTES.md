# observability module — build notes

**Rides:** AI SDK 7 telemetry redesign / `@ai-sdk/otel` (2026-07-09) — same idea, Spring/Micrometer implementation.

**What it does:** `RouterTelemetryAspect` is an `@Around` aspect on `SmartRouteService.route(..)` that times every call and records tier, cost (USD), latency, and call counts to Micrometer via `TelemetryService`. `GET /observability/metrics` returns a rollup; full metrics also flow through Actuator/Micrometer (Prometheus/OTLP-ready).

**Why an aspect:** telemetry stays cross-cutting — cost/latency are captured no matter which module (rag, memory, governance) originated the model call, without each module wiring its own metrics.

**Deps added:** `spring-boot-starter-actuator` (Micrometer) + `spring-boot-starter-aop`.
**Tests:** aggregation of calls + cost across tiers, empty-start — using an in-memory `SimpleMeterRegistry`, no live calls. `RouterTelemetryAspectTest` separately verifies the `@Around` advice itself actually fires, via a real `AspectJProxyFactory` proxy — not just that `TelemetryService` works as a POJO.

**Gotcha #1 (resolved 2026-07-12):** the pointcut was `execution(* ...SmartRouteService.route(..))` — name-exact, so it silently missed `routeFrom(..)` once that method existed (added for the governance DOWNGRADE fix, see `gateway-NOTES.md`). Any DOWNGRADE-routed request vanished from telemetry with no error. Fixed by widening to `route*(..)`; caught by `RouterTelemetryAspectTest`, which fails without the fix.

**Gotcha #2 (resolved 2026-07-13):** the aspect only recorded telemetry on a successful return — a model call that failed partway through escalation (Luna succeeds, Terra's call itself throws) meant `pjp.proceed()` threw before the `telemetry.record(...)` line, so Luna's real, already-incurred cost never reached telemetry at all. The aspect now also catches `PartialRouteException` (see `routing-NOTES.md`), records the partial cost (only when non-zero, so a first-attempt failure doesn't add a zero-cost "call" to the counts) before rethrowing. See `RouterTelemetryAspectTest.recordsPartialCostWhenAModelCallFailsMidEscalation`.
