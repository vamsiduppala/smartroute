# gateway module — build notes

**Rides:** ties together routing, governance, and guardrails (each rides its own launch — see their NOTES files) into the single "AI Gateway" entry point.

**What it does:** `GatewayService.handle(tenant, prompt)` runs a fixed pipeline:
1. **rate limit** — `RateLimiter.tryAcquire(tenant)`; over-rate tenants are shed with `rate-limited` before any work (see `governance-NOTES.md`).
2. **guardrails** — `PromptInjectionScanner.scan()`; flagged prompts are rejected before touching a model.
3. **cache** — `ResponseCache.get(prompt)`; an exact-match hit returns the prior `RouteResult` for $0 (via `GatewayResult.cached()`), skipping classify/budget/routing entirely. Cross-tenant by design — identical prompts yield identical answers — so the saving is shared.
4. **governance** — estimate cost at the classifier's start tier, then `BudgetGuard.evaluateAndReserve()` → ALLOW / DOWNGRADE / REJECT, atomically reserving the estimate unless REJECT (see `governance-NOTES.md`'s TOCTOU gotcha for why this has to be atomic).
5. **routing** — delegate to `SmartRouteService`.
6. **reconciling** — `ledger.add(tenant, actualCost - estimate)` trues up the reservation to the real cost once known; on a non-`PartialRouteException` failure the reservation is fully *reversed* so it never leaks as phantom spend. A passing result is then written to the cache.

(Observability is cross-cutting — `RouterTelemetryAspect` wraps `SmartRouteService.route(..)`/`routeFrom(..)` directly, so it fires no matter which caller drove the call. Cache hits never reach the router, so they aren't counted as model calls — correct, since none happened.)

**Gotcha #1 (resolved 2026-07-12):** DOWNGRADE used to be a decision in name only. `BudgetGuard` computed it correctly, but `GatewayService` only branched on REJECT — DOWNGRADE fell through to the exact same `route()` call as ALLOW, so the tier was still whatever the classifier picked, while the response label claimed "DOWNGRADE" regardless. Fixed by adding `SmartRouteService.routeFrom(prompt, validator, startTier)` so the gateway can force `Tier.LUNA` on DOWNGRADE instead of just labeling the response. See `GatewayServiceTest.downgradeDecisionActuallyForcesLunaInsteadOfRoutingNormally`.

**Gotcha #2 (resolved 2026-07-13):** step 4 (booking) never ran if the router threw partway through escalation — a Luna call that succeeded (real cost) followed by a Terra call that itself failed (network error, 5xx) meant `ledger.add(...)` never executed, so real spend went completely untracked against the tenant. `handle()` now catches `PartialRouteException` (see `routing-NOTES.md`), books `partial.partialResult().costUsd()` to the ledger, then rethrows so the failure still surfaces to the caller the same way it always did — just with the ledger now accurate regardless of where escalation broke. See `GatewayServiceTest.partialRouteFailureStillBooksWhateverCostWasIncurredBeforePropagating`.

**Caching (the cost lever):** `ResponseCache` is an exact-match, TTL + size-bounded store (`smartroute.cache.{ttl-seconds,max-entries}`, defaults 300s / 10k) keyed on the prompt. The gateway checks it after rate-limit + guardrails and, on a passing route, populates it — so a repeated prompt is served for $0 with `budgetDecision=CACHED` and a note stating the amount saved. Rate limiting and guardrails still apply to a cache hit (it's still a request); only classification, budget booking, and the model call are skipped. Honest limits (documented on the class): exact-match only (no semantic similarity), not an LRU, and only passing results are cached.

**Endpoints:** `POST /gateway/route` `{"tenant":"...","prompt":"..."}` — the full pass. (`POST /route` is routing alone, with no guardrails/governance/cache/ledger wrapping.)
**Tests:** `GatewayServiceTest` (blocked-by-injection, blocked-by-budget, rate-limited, cache-hit-not-re-routed, happy path, downgrade-forces-Luna, partial-failure-still-books-ledger, reservation-reversed-on-unexpected-failure — router mocked, offline) + `GatewayControllerTest` (web layer, service mocked) + `GatewayIntegrationTest` (ALLOW, blocked, DOWNGRADE, Prometheus scrape — real embedded server; cache disabled via `ttl-seconds=0` to isolate routing/spend assertions). `ResponseCacheTest` covers the cache in isolation (hit/miss/expiry/refresh/size-bound/reclaim).
