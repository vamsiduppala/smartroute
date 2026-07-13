# gateway module — build notes

**Rides:** ties together routing, governance, and guardrails (each rides its own launch — see their NOTES files) into the single "AI Gateway" entry point.

**What it does:** `GatewayService.handle(tenant, prompt)` runs a fixed pipeline:
1. **guardrails** — `PromptInjectionScanner.scan()`; flagged prompts are rejected before touching a model.
2. **governance** — estimate cost at the classifier's start tier, then `BudgetGuard.evaluate()` → ALLOW / DOWNGRADE / REJECT.
3. **routing** — delegate to `SmartRouteService`.
4. **booking** — the *actual* spend (not the estimate) is added to the tenant's `SpendLedger`.

(Observability is cross-cutting — `RouterTelemetryAspect` wraps `SmartRouteService.route(..)`/`routeFrom(..)` directly, so it fires no matter which caller drove the call.)

**Gotcha #1 (resolved 2026-07-12):** DOWNGRADE used to be a decision in name only. `BudgetGuard` computed it correctly, but `GatewayService` only branched on REJECT — DOWNGRADE fell through to the exact same `route()` call as ALLOW, so the tier was still whatever the classifier picked, while the response label claimed "DOWNGRADE" regardless. Fixed by adding `SmartRouteService.routeFrom(prompt, validator, startTier)` so the gateway can force `Tier.LUNA` on DOWNGRADE instead of just labeling the response. See `GatewayServiceTest.downgradeDecisionActuallyForcesLunaInsteadOfRoutingNormally`.

**Gotcha #2 (resolved 2026-07-13):** step 4 (booking) never ran if the router threw partway through escalation — a Luna call that succeeded (real cost) followed by a Terra call that itself failed (network error, 5xx) meant `ledger.add(...)` never executed, so real spend went completely untracked against the tenant. `handle()` now catches `PartialRouteException` (see `routing-NOTES.md`), books `partial.partialResult().costUsd()` to the ledger, then rethrows so the failure still surfaces to the caller the same way it always did — just with the ledger now accurate regardless of where escalation broke. See `GatewayServiceTest.partialRouteFailureStillBooksWhateverCostWasIncurredBeforePropagating`.

**Endpoints:** `POST /gateway/route` `{"tenant":"...","prompt":"..."}` — the full pass. (`POST /route` is routing alone, with no guardrails/governance/ledger wrapping.)
**Tests:** `GatewayServiceTest` (blocked-by-injection, blocked-by-budget, happy path, downgrade-forces-Luna, partial-failure-still-books-ledger — router mocked, offline) + `GatewayControllerTest` (web layer, service mocked).
