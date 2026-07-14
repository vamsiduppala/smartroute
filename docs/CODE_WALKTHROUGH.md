# Code walkthrough — one request, end to end

This traces a single `POST /gateway/route` call through every layer of the gateway, in
execution order, with links to the exact code. If you're reviewing this repo and have five
minutes, read this file: it's the shortest path to understanding how the pieces fit and *why*
each one exists.

The one-line thesis: **pay flagship (`Sol`) prices only when the cheaper tiers actually fail
the caller's own validity check — never by default.** Everything below serves that.

```
POST /gateway/route
      │
      ▼
GatewayController ──► GatewayService.handle(tenant, prompt)
                          │
      1. rate limit ──────┤  RateLimiter.tryAcquire()            shed excess load per tenant, before any work
      2. guardrails ──────┤  PromptInjectionScanner.scan()      reject before any model call
      3. cache      ──────┤  ResponseCache.get(prompt)          exact-match hit → return for $0, skip the rest
      4. classify   ──────┤  ComplexityClassifier.classify()    pick a *starting* tier, no model call
      5. governance ──────┤  BudgetGuard.evaluateAndReserve()    atomic check-and-reserve (closes a TOCTOU race)
      6. route      ──────┤  SmartRouteService.route()           try cheap → escalate until the validator passes
                          │        └─ RouterTelemetryAspect      records cost/latency per attempt (cross-cutting)
      7. reconcile  ──────┘  SpendLedger.add(actual − estimate)  true up the reservation to real cost
                          │
                          ▼
                     GatewayResult (ok | blocked)
```

---

## 0. The entry point

[`GatewayController.route`](../src/main/java/com/vamsi/smartroute/gateway/GatewayController.java)
is a thin REST adapter. It does exactly one thing beyond delegating: it validates input at the
edge — a null/blank prompt throws `IllegalArgumentException` (mapped to a clean `400` by
[`GlobalExceptionHandler`](../src/main/java/com/vamsi/smartroute/web/GlobalExceptionHandler.java)),
and a missing tenant defaults to `"default"`. All real work lives in
[`GatewayService.handle`](../src/main/java/com/vamsi/smartroute/gateway/GatewayService.java) so
it stays unit-testable without a servlet.

## 1. Rate limit — shed excess load first

[`RateLimiter.tryAcquire`](../src/main/java/com/vamsi/smartroute/governance/RateLimiter.java) is the
outermost gate. Each tenant gets a **token bucket** — a burst `capacity` refilled at a steady
`refillPerSec` — and one token is consumed per request; when the bucket is empty the request is
refused with `GatewayResult.blocked("rate-limited", …)` before any scanning, classification, or
model work happens. This is load-shedding: cost caps (step 5) bound *spend*, but a tenant can fire
cheap calls that each pass the budget check individually yet still overwhelm capacity, so rate has
to be bounded separately.

Like the ledger, the refill-and-consume runs inside one atomic `ConcurrentHashMap.compute`, so two
concurrent requests for the same tenant can't both drain the last token. Time is injected (a
`LongSupplier` of nanos) so tests drive it deterministically instead of sleeping.

## 2. Guardrails — reject before spending a cent

[`PromptInjectionScanner.scan`](../src/main/java/com/vamsi/smartroute/guardrails/PromptInjectionScanner.java)
runs next. It's a heuristic pattern scan for the common override/exfiltration shapes ("ignore
previous instructions", "reveal your system prompt", "leak the api key", …). If it flags, the
request short-circuits to `GatewayResult.blocked("prompt-injection", …)` — **no model is ever
called**, so a hostile prompt costs nothing.

It's deliberately a heuristic, not an ML classifier: cheap, explainable, zero added latency, and
honest about being a first line of defense rather than a guarantee (see
[guardrails-NOTES.md](guardrails-NOTES.md)).

## 3. Cache — the cheapest call is the one you never make

[`ResponseCache.get`](../src/main/java/com/vamsi/smartroute/gateway/ResponseCache.java) is checked
next. If this exact prompt was answered before (within the TTL), its already-computed
[`RouteResult`](../src/main/java/com/vamsi/smartroute/routing/RouteResult.java) is returned
immediately via `GatewayResult.cached(...)` — **classification, budget reservation, and the model
call are all skipped**, and the response carries `budgetDecision: CACHED` with a note stating the
amount saved. On a miss, the pipeline continues; a *passing* result is written back to the cache in
step 7 so the next identical prompt is free.

The cache is keyed on the prompt, not the tenant: an identical prompt yields the same answer no
matter who asks, so sharing across tenants maximizes the saving. It's an exact-match, TTL +
size-bounded store (not an LRU, no semantic similarity) — the honest limits are documented on the
class. Rate limiting and guardrails already ran, so a cache hit is still counted against the
tenant's rate and still screened for injection; only the expensive work is skipped.

## 4. Classify — pick a starting tier with no model call

[`ComplexityClassifier.classify`](../src/main/java/com/vamsi/smartroute/routing/ComplexityClassifier.java)
scores the prompt from its text alone — length, presence of code, and "hard signal" keywords
(`prove`, `derive`, `optimize`, `refactor`, `concurrency`, `algorithm`, …). The score maps to a
*starting* tier: `Luna` (cheapest) / `Terra` / `Sol`.

The key design call: this only has to be **roughly right**. It errs toward the cheaper tier on
purpose, because step 6's escalation catches any undershoot. A wrong-but-cheap guess costs one
extra Luna attempt; a wrong-but-expensive guess would waste Sol money on every request. That
asymmetry is why the heuristic is tuned to under-, not over-, estimate.

## 5. Governance — atomic check-and-reserve

Before routing, the gateway estimates cost at the start tier (`~4 chars/token`, 256 output
tokens) and calls
[`BudgetGuard.evaluateAndReserve`](../src/main/java/com/vamsi/smartroute/governance/BudgetGuard.java).

This is the subtle part. A naive "read spend → decide → later write spend" has a **TOCTOU race**:
two concurrent requests for the same tenant both read spend *below* cap, both proceed, and
together blow past it. `evaluateAndReserve` decides **and** books the reservation in one atomic
step against [`SpendLedger`](../src/main/java/com/vamsi/smartroute/governance/SpendLedger.java),
so the second request sees the first's reservation already on the books. It returns one of:

- `ALLOW` — under cap; route normally from the classifier's tier.
- `DOWNGRADE` — the classifier's tier would breach cap, but `Luna` fits. The gateway then
  **actually forces `Luna`** via `router.routeFrom(prompt, …, Tier.LUNA)` — it carries out the
  decision rather than just labeling the response and routing at the expensive tier anyway.
- `REJECT` — even `Luna` won't fit. Short-circuit to `blocked("budget-exceeded", …)`.

## 6. Route — try cheap, escalate on failure

[`SmartRouteService.routeFrom`](../src/main/java/com/vamsi/smartroute/routing/SmartRouteService.java)
is the engine. Starting at the chosen tier, it loops:

1. Call the model at the current tier.
2. Add that attempt's real token cost (billed at **that tier's** rate — a failed cheap attempt
   still costs its cheap price).
3. Run the caller's `Validator` against the answer. Pass → done. Fail → `tier.escalate()` and
   loop (`Luna → Terra → Sol → null`).

Cost accumulates *across* attempts, so the reported cost is honest about escalation overhead —
it is not just the final tier's price. Two correctness details worth calling out, both added
after independent review passes found the gap:

- **The whole attempt is inside one `try`** — the model call *and* parsing its response. A
  malformed response (null result/usage) is "this attempt produced nothing usable," exactly like
  the call throwing, and both must surface as
  [`PartialRouteException`](../src/main/java/com/vamsi/smartroute/routing/PartialRouteException.java)
  so earlier attempts' real cost isn't silently dropped.
- **Pricing lives in the [`Tier`](../src/main/java/com/vamsi/smartroute/model/Tier.java) enum**,
  the single source of truth for the real published GPT-5.6 per-million-token rates.

### 6a. Telemetry — cross-cutting, per attempt

[`RouterTelemetryAspect`](../src/main/java/com/vamsi/smartroute/observability/RouterTelemetryAspect.java)
wraps `route*(..)` with an AOP `@Around` advice and records **one telemetry entry per attempt**,
not one per outer call. An escalation that hits Luna → Terra → Sol books three real API calls and
is counted as three — attributing cost and latency to the tier that actually incurred them.
Because the pointcut is `route*` (not `route`), it also covers the forced-tier `routeFrom` path a
`DOWNGRADE` takes, and on a `PartialRouteException` it still records the attempts that succeeded
before the failure.

## 7. Reconcile — true up the ledger

Back in `GatewayService`, once the actual cost is known, the gateway books the difference:
`ledger.add(tenant, result.costUsd() − estimate)`. The reservation from step 5 was an estimate;
this line trues it up to reality. On a `PartialRouteException` the same reconciliation runs
against the partial actual cost *before* the exception propagates — so a mid-route failure never
leaves the reservation permanently over- or under-stating the tenant's spend. (On any *other*
unexpected failure out of routing the reservation is fully *reversed* instead, so it never leaks as
phantom spend — see `GatewayService`.)

Finally, if the answer passed its validator, the gateway writes it to the `ResponseCache` (step 3)
so the next identical prompt is served for free.

The result comes back as
[`GatewayResult.ok(result, decision)`](../src/main/java/com/vamsi/smartroute/gateway/GatewayResult.java),
carrying the tier used, attempt count, per-attempt cost breakdown, and the governance decision.

---

## Where to look next

| You want to… | Read |
|---|---|
| See the routing economics without an API key | [simulation-results.md](simulation-results.md) — credit-free projection on real pricing |
| Understand the design trade-offs & known limits | [ENGINEERING.md](ENGINEERING.md) |
| Go module by module | the [`*-NOTES.md`](README.md#per-module-design-notes) files |
| Run it yourself, keyless | the demo profile — [main README](../README.md#quickstart) |

Every number this system reports is produced at run time from real token counts and the published
`Tier` pricing — nothing in the repo hardcodes a result.
