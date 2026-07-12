# observability module — build notes

**Rides:** AI SDK 7 telemetry redesign / `@ai-sdk/otel` (2026-07-09) — same idea, Spring/Micrometer implementation.

**What it does:** `RouterTelemetryAspect` is an `@Around` aspect on `SmartRouteService.route(..)` that times every call and records tier, cost (USD), latency, and call counts to Micrometer via `TelemetryService`. `GET /observability/metrics` returns a rollup; full metrics also flow through Actuator/Micrometer (Prometheus/OTLP-ready).

**Why an aspect:** telemetry stays cross-cutting — cost/latency are captured no matter which module (rag, memory, governance) originated the model call, without each module wiring its own metrics.

**Deps added:** `spring-boot-starter-actuator` (Micrometer) + `spring-boot-starter-aop`.
**Tests:** aggregation of calls + cost across tiers, empty-start — using an in-memory `SimpleMeterRegistry`, no live calls.
