# BUILD_NOTES — SmartRoute (raw material for the post)

**Launch ridden:** GPT-5.6 (Sol/Terra/Luna), 2026-07-09. Ship target: within days of launch.

## What's actually new about GPT-5.6
- Not one model — a **three-tier family** with distinct API ids (`gpt-5.6-sol`, `gpt-5.6-terra`, `gpt-5.6-luna`) and a 5× price spread (Luna $1/$6, Terra $2.5/$15, Sol $5/$30 per Mtok).
- Sol is marketed as +54% token-efficient on coding vs. the prior flagship — worth *measuring*, not repeating.
- The tiering is the interesting part: it turns "which model?" into a routing problem you can automate.

## The build, step by step
1. Spring Boot 3.4 + `spring-ai-openai-spring-boot-starter` (Spring AI 1.0). No custom SDK — GPT-5.6 is on the OpenAI-compatible surface.
2. `Tier` enum carries model id + pricing + `escalate()`.
3. `ComplexityClassifier` picks a **starting** tier from the prompt with a cheap regex/length heuristic — zero model calls. It only has to be roughly right because escalation catches misses.
4. `SmartRouteService` walks up the tiers until a `Validator` accepts the answer.
5. `EvalRunner` benchmarks routed vs. always-Sol on a fixed task set.

## Gotchas / things the docs don't say
- **The starter got renamed at 1.0 GA.** Most tutorials/RC docs still say `spring-ai-openai-spring-boot-starter`; on Spring AI **1.0.0 GA** the id is `spring-ai-starter-model-openai`. The old one won't resolve against the BOM (`'version' ... is missing`). Caught this by actually compiling, not reading docs.
- **Per-request model override:** you don't need three beans. `OpenAiChatOptions.builder().model(id)` on the `Prompt` overrides the `application.yml` default per call. That's the whole trick for multi-tier routing in Spring AI.
- **Token usage getters have drifted across Spring AI versions** (Integer vs. Long on `Usage.getPromptTokens()`), which is why `asLong()`/`num()` normalize via `Number`. Pin your Spring AI version and don't assume the type.
- **Routing is NOT free — this is the honest headline.** A failed Luna attempt *before* escalating to Sol means you paid for Luna **and** Sol. If the classifier under-shoots on a hard task, routing can cost *more* than going straight to Sol. Cost is therefore accumulated **per attempt at that attempt's own rate** (`SmartRouteService`), not at the final tier's rate — the naive version understates it.
- **Savings are only real at quality parity.** The eval reports routed-pass vs. baseline-pass side by side; a savings % is meaningless if routed answers pass fewer tasks. Report both or you're lying with a number.

## Verification status (2026-07-12)
- Compiles clean on Spring AI 1.0.0 GA + JDK 21.
- 39 tests green: the original 25 unit tests plus a new MockMvc web-layer suite covering all 5 controllers (routing, gateway, governance, guardrails, observability).
- Live GPT-5.6 eval blocked: owner's OpenAI account has no billing (`insufficient_quota`). Worked around with `RoutingSimulationTest` — a labeled projection on real published pricing (~54.6% saved), zero credits. Live measurement is optional future work, not a blocker.
- Added OpenAPI/Swagger UI (springdoc) and Kubernetes manifests (Deployment/Service/Secret template), both API-free hardening. Booted the app on a scratch port to confirm Swagger UI, `/v3/api-docs`, and the Actuator liveness/readiness probe groups all actually work — not just "compiles."

## Deferred (2026-07-12)
- **rag / memory / longcontext modules** — permanently on hold: owner's Anthropic account has no API credits, and unlike the GPT-5.6 routing simulation, RAG and long-context genuinely require a live model call to be real (retrieval quality, actual 1M-context behavior) — a stub client would just be hollow scaffolding. Not worth building until credits exist. Everything else on this project stays API-free and keeps moving.
- **Docker image build verification** — Docker CLI is installed but the daemon isn't running (Docker Desktop isn't even started) in this environment; booting it here is slow/uncertain (tried once, gave up after ~70s). `Dockerfile` has never actually been built and run, just read for correctness. Revisit when Docker Desktop is up: `docker build -t smartroute:0.1.0 .` then a smoke-test container run.
- ~~k8s manifest schema validation~~ **RESOLVED 2026-07-13** — no live cluster is reachable in this sandbox, so `kubectl apply --dry-run` couldn't work (tried, failed on the auth-walled proxy this environment routes localhost through). Used the `kubernetes-validate` Python package instead, which validates against real bundled Kubernetes OpenAPI schemas with no cluster needed: all 3 manifests (`deployment.yaml`, `service.yaml`, `secret.example.yaml`) pass strict validation against the k8s 1.31 schema. Actual runtime behavior (does the Deployment actually schedule, do the probes actually pass) still needs a real cluster — that part stays unverified — but the manifests are no longer just "read for correctness," they're schema-checked.

## Known limitations (found via independent review, 2026-07-13 — real, verified, deliberately not "fixed" yet)
Two rounds of independent review this session found a lot: 6 real bugs, all fixed (DOWNGRADE
never enforced; telemetry pointcut missing `routeFrom`; `GlobalExceptionHandler` shadowing 400,
then separately 405/415; `ToolDriftDetector` ignoring trailing JSON, then a second fix for that
FIRST fix breaking its own key-reordering guarantee; a missing `Allow` header on 405s) plus one
claimed bug (bad `Accept` header -> 500) that a test proved doesn't actually reproduce in this
app's configuration -- not fixed, because there was nothing to fix. The 5 items below are real
too, but each needs an actual design decision or a larger-blast-radius refactor rather than a
safe mechanical fix, so they're documented instead of rushed — consistent with "do NOT invent
these" below: an honest known-limitation beats a hasty fix that might be subtly wrong.

- **No authentication or authorization anywhere in the app.** Not specific to any one endpoint — flagged because a second review pass singled out the new `GET /governance/spend` (admin-style cross-tenant dump) as a meaningful *increase* in exposure vs. the existing per-tenant `GET /governance/spend/{tenant}` (which at least requires already knowing a tenant name). True, and worth a second look before anything here is internet-facing, but it's a whole-app scope decision (add Spring Security, API keys, something) — not a targeted fix, and this is a demo/portfolio gateway, not a production one. Noting it plainly rather than bolting on partial auth for one endpoint.
- **DOWNGRADE lowers the floor, not a hard ceiling.** `GatewayService` forces `routeFrom(..., Tier.LUNA)` on a DOWNGRADE decision, but `SmartRouteService`'s escalation loop still climbs Luna→Terra→Sol if Luna's answer fails validation — a downgraded tenant under budget pressure can still end up billed at Sol rates. This is deliberate, not an oversight: hard-capping at Luna regardless of answer quality would violate this project's own quality-parity principle (see the eval gotchas above) by silently accepting bad answers to save money. Documented, not changed.
- **Budget check uses a single-attempt estimate; actual spend can be ~3x higher.** `GatewayService.handle()` checks `BudgetGuard` against `classification.startTier().costUsd(...)` for ONE attempt, but real spend (`result.costUsd()`) sums cost across every escalation attempt (the same per-attempt-cost gotcha above). A request that estimates as comfortably ALLOW can, after escalating through all three tiers, book meaningfully more than what was checked against the cap. Pre-computing a worst-case estimate would require walking the full escalation chain before spending anything, which isn't possible (you don't know if Luna will pass until you call it).
- **Budget check and spend booking aren't atomic (TOCTOU race).** `budgetGuard.evaluate()` (read) and `ledger.add()` (write, after the model call) are separate steps with no lock spanning them. Two concurrent requests for the same tenant near their cap can both read the same pre-spend total, both get ALLOW, and both book spend afterward — cumulative spend can exceed the cap under concurrent load. `SpendLedger`/`BudgetGuard` are internally thread-safe (`ConcurrentHashMap`/atomics) but that doesn't make the check-then-act sequence atomic. A real fix needs a per-tenant lock or a reserve-then-commit pattern, not just swapping data structures.
- **A model-call exception mid-escalation loses tracking of already-incurred cost.** `SmartRouteService`'s while-loop has no try/catch around `chatModel.call()`. If Luna succeeds (real cost incurred, answer rejected) and then Terra's call throws (network error, 5xx, etc.), the exception propagates before a `RouteResult` is ever built, so Luna's already-real cost never reaches `SpendLedger` or `TelemetryService`. Not observed live (no billing to trigger it), found by tracing the code path.
- **Observability records once per `route()`/`routeFrom()` call, not once per actual model call.** `RouterTelemetryAspect` wraps the whole escalation loop as one join point, so an escalation that makes 3 real OpenAI calls (Luna, Terra, Sol) is recorded as 1 call attributed entirely to `tier=SOL` in `callsByTier()`/Micrometer's per-tier counters. Total cost (`totalCostUsd`) IS accurate (it's the aggregate from `RouteResult.costUsd()`), but per-tier call-count and per-tier cost breakdowns undercount real API usage. A correct fix needs `RouteResult` to carry a per-attempt breakdown (tier, tokens, cost) rather than just the aggregate, which changes a record used across ~7 files (controllers, tests, `EvalRunner`) — real refactor, not a one-line fix.

## What to measure before posting (do NOT invent these)
- Real routed vs. Sol-only $ on the task set (run `--eval`).
- How often the classifier's start tier was right (attempts == 1).
- The break-even: at what escalation rate does routing stop saving money?

## Post angle (draft seed — not published)
Lead: "GPT-5.6 dropped 3 days ago with 3 price tiers. I wired Spring AI to route across them and measured whether 'just use the cheapest that works' actually saves money." Deliver the per-attempt-cost gotcha + the parity caveat. Java/Spring AI implementation = almost nobody else's angle.
