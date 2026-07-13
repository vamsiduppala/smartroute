# BUILD_NOTES — SmartRoute (raw material for the post)

## HANDOFF — pick up here (written 2026-07-13T07:25:48Z, end of a Sonnet 5 hardening session)
Next session runs on Opus 4.8, cold start. Repo is fully committed and pushed (`origin/main` ==
local HEAD), 76 tests green, CI green. Cadence rule in effect: 1 hour of active-work-only,
back-to-back chunks, no questions, deferred list for blockers, pushing pre-authorized — then a
genuine 5-hour break before the next hour block. This session's hour ended at 2026-07-13T07:25:48Z;
next block should start ~2026-07-13T12:25:48Z.

**State:** all 3 previously-deferred "known limitations" from earlier today are now fixed
(mid-escalation cost loss, TOCTOU budget race, telemetry granularity — see "Known limitations"
section below, all struck through). Three independent-review passes this session found 8+ real
bugs, all fixed and verified (revert-confirm-restore rigor on each). Dependency CVEs researched
and patched where real (Jackson, Tomcat, Spring Framework — see Verification status 2026-07-13).
Maven wrapper added with a verified checksum. No secrets anywhere in git history.

**Priority order for the next block:**
1. **Run another independent-review pass** (see feedback memory for the exact pattern: spawn a
   subagent with no context beyond "read these actual files, look for X", verify its claims
   independently, rigor-check any fix). This found real bugs 3 times running this session —
   don't assume the well is dry. Areas not yet given this treatment: `Tier`, `ComplexityClassifier`
   in isolation, the `k8s/`/`Dockerfile` content itself (only linted, never deeply reviewed),
   `application.yml` property binding correctness.
2. **Recheck Docker daemon / k8s cluster availability** — both deferred purely on environment
   grounds (no daemon, no cluster), not code issues. A quick `docker info` / `kubectl cluster-info`
   costs nothing; if either is available now, there's real verification work waiting
   (`docker build`, a smoke-test container run, `kubectl apply --dry-run` or an actual `kind`
   cluster) that was impossible in this session's sandbox.
3. **Mockito self-attach warning** (see "Minor known item" below) — still just a future-JDK
   compatibility warning, not a current failure, but if there's a full hour with nothing more
   urgent, wiring the `-javaagent` properly via `maven-dependency-plugin` is a legitimate,
   bounded task.
4. **EvalRunner robustness** — currently one exception anywhere in the task loop kills the whole
   benchmark run with no partial results written. Lower priority (it's a one-shot manual CLI
   tool, not a production path — see the "Deferred" reasoning already in this file), but worth
   a look if other priorities are clear.
5. **The actual blog post** — `BUILD_NOTES.md`'s "Post angle" section (bottom of this file) is
   still an unpublished draft seed. Everything it needs (the per-attempt-cost gotcha, the parity
   caveat, real simulation numbers) has been ready for a while. This is a genuinely different
   kind of task (writing, not code) — flag it to the user rather than assuming it's in scope for
   an autonomous hardening loop.

**Do NOT re-attempt:** rag/memory/longcontext modules (permanently deferred, no Anthropic
credits — don't build these, don't ask about a key). Do NOT relitigate the DOWNGRADE
soft-ceiling or no-auth items in "Known limitations" below — both are deliberate, already-argued
design decisions, not bugs.

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

## Verification status (2026-07-13)
- Spring Boot 3.4.13, Spring AI 1.0.9, springdoc 2.8.17 (patch-bumped from 3.4.1/1.0.0/2.8.3 — see Deferred below for why not the available major versions).
- 66 tests green. Two independent review passes this session found and fixed 6 real bugs (BudgetGuard DOWNGRADE never enforced; observability pointcut missing `routeFrom`; `GlobalExceptionHandler` shadowing first 400 then 405/415; `ToolDriftDetector` ignoring trailing JSON, then a follow-up fix for a regression the FIRST fix introduced; a missing RFC-required `Allow` header on 405s) plus correctly rejected one claimed bug (bad `Accept` header) that a test proved doesn't reproduce here.
- Full offline+online clean rebuild passes (`rm -rf target && mvn test`), matching what a fresh CI checkout actually does, not just incremental local builds.
- CI now runs `mvn package` (was only ever `test`) — the Dockerfile depends on `package` succeeding (it triggers the spring-boot-maven-plugin's repackage goal into an executable jar) and that had never actually been exercised in CI. Verified locally too: ran `mvn package`, confirmed the manifest is a real Spring Boot executable jar, then ran `java -jar target/smartroute-0.1.0.jar` directly (bypassing Maven entirely, a genuinely different code path than `spring-boot:run`'s exploded classes) and confirmed Swagger UI and the app's error handling behave identically.
- Researched (not just trusted) what the 3.4.1→3.4.13 bump actually fixed: Spring AI 1.0.9 patches CVE-2026-22738 (critical, CVSS 9.8, SpEL injection RCE in `SimpleVectorStore` — not exploitable here, this codebase never uses a vector store, but patched regardless since it's a transitive dependency now). Spring Boot 3.4.13's own BOM still manages Jackson at 2.18.5, which is affected by CVE-2026-54515 (case-insensitive deserialization can bypass per-property `@JsonIgnoreProperties`) — not fixed by the Spring Boot bump alone. Checked whether the vulnerable pattern is used anywhere in this codebase (`@JsonIgnoreProperties` / `@JsonFormat(ACCEPT_CASE_INSENSITIVE_PROPERTIES)`) — it isn't, so not currently exploitable either, but overrode `jackson-bom.version` to 2.18.9 (the patched release) regardless. Both CVEs verified independently against spring.io/NVD/GitHub advisories, not taken on a subagent's word alone.
- Same due-diligence pass, Tomcat: 3.4.13's BOM manages `tomcat-embed-core` at 10.1.50, six patch releases behind the latest 10.1.57. Fetched tomcat.apache.org's official security page directly (a subagent's summary of the same page was self-contradictory — said a CVE was both "fixed in 10.1.50" and "10.1.50 is vulnerable to it" in the same response) to get it right: CVE-2026-55956 (Moderate, security constraints on the default servlet can be ignored) plus two Low-severity issues, none fixed until 10.1.56. Not exploitable here (no web.xml, no Spring Security, no servlet-level security constraints, no clustering), overrode `tomcat.version` to 10.1.57 regardless.
- Third and final leg of the same pass, Spring Framework itself: 3.4.13's BOM manages it at 6.2.15. CVE-2026-22740 (Medium: WebFlux multipart temp files not always deleted, DoS via disk exhaustion) affects 6.2.0-6.2.17, fixed in 6.2.18. This app runs Spring MVC, not a WebFlux server, and never handles multipart requests — `spring-webflux` is present only transitively (Spring AI's reactive HTTP client), not exploitable even in the narrower sense the other two were. Overrode `spring-framework.version` to 6.2.19 (latest patch) anyway, tested more carefully than the leaf-dependency overrides since this is the core of everything: confirmed every Spring module resolves consistently (no split-brain versions), full clean rebuild, all 68 tests including the real end-to-end `GatewayIntegrationTest`.
- Checked AspectJ too (relevant since `RouterTelemetryAspect` depends on it): `aspectjweaver` resolves to 1.9.25.1, already the latest release, no known CVEs.
- Added a Maven wrapper (`mvnw`/`mvnw.cmd`, pinned to 3.9.9) and switched CI to use it — this project had none before, unusual for a Spring Initializr-style project. Caught a real regression from the wrapper commit itself by actually watching the CI run instead of assuming green: `mvnw` lost its executable bit going through Windows Git (which doesn't track POSIX permissions natively), so CI failed with "Permission denied" / exit 126. Fixed with `git update-index --chmod=+x mvnw`, confirmed the next CI run passed.

## Minor known item (not fixed, low priority)
Every test run logs: *"Mockito is currently self-attaching to enable the inline-mock-maker. This will no longer work in future releases of the JDK."* Real fix is wiring Mockito as a proper `-javaagent` via `maven-dependency-plugin`'s `properties` goal + a surefire `argLine` — a genuine build-config change, not a one-liner, and JDK 21 (what this project targets) still supports self-attaching today; this is a future-compatibility warning, not a current failure. Left alone rather than rushing a build-config change with limited time to verify it thoroughly.

## Deferred (2026-07-12)
- **rag / memory / longcontext modules** — permanently on hold: owner's Anthropic account has no API credits, and unlike the GPT-5.6 routing simulation, RAG and long-context genuinely require a live model call to be real (retrieval quality, actual 1M-context behavior) — a stub client would just be hollow scaffolding. Not worth building until credits exist. Everything else on this project stays API-free and keeps moving.
- **Docker image build verification** — still can't actually build/run the image. Tried booting Docker Desktop twice this session (once ~70s, once longer while doing other work in parallel) — it never reaches a ready daemon in this sandbox, most likely missing virtualization support (Hyper-V/WSL2) the environment doesn't expose. Cleaned up the resulting Docker Desktop processes both times rather than leaving them running indefinitely for nothing. What *is* now verified without needing the daemon: ran `hadolint` (the standard Dockerfile linter, downloaded as a standalone binary since no daemon means no `docker run hadolint/hadolint`) against `Dockerfile` — zero warnings, clean exit. So the Dockerfile passes static best-practices linting; it has still never actually been built into a real image. Revisit on a machine/environment with a real Docker daemon: `docker build -t smartroute:0.1.0 .` then a smoke-test container run.
- ~~k8s manifest schema validation~~ **RESOLVED 2026-07-13** — no live cluster is reachable in this sandbox, so `kubectl apply --dry-run` couldn't work (tried, failed on the auth-walled proxy this environment routes localhost through). Used the `kubernetes-validate` Python package instead, which validates against real bundled Kubernetes OpenAPI schemas with no cluster needed: all 3 manifests (`deployment.yaml`, `service.yaml`, `secret.example.yaml`) pass strict validation against the k8s 1.31 schema. Actual runtime behavior (does the Deployment actually schedule, do the probes actually pass) still needs a real cluster — that part stays unverified — but the manifests are no longer just "read for correctness," they're schema-checked.
- **Major-version dependency upgrades not taken.** Bumped everything to the latest patch within its already-pinned major.minor line (see below), but left three major versions on the table: Spring Boot 4.1.0 (currently 3.4.x), Spring AI 2.0.0 (currently 1.0.x), springdoc-openapi 3.0.3 (currently 2.8.x). Each is a real migration with likely breaking changes across the whole app, not a patch bump — out of scope for a hardening pass, especially the day after carefully documenting the exact 1.0.0 GA Spring AI starter rename gotcha. Worth a dedicated session if/when there's reason to move (e.g. a security advisory against the current major).

## Known limitations (found via independent review, 2026-07-13 — real, verified, deliberately not "fixed" yet)
Three rounds of independent review this session found a lot: at least 8 real bugs, all fixed
(DOWNGRADE never enforced; telemetry pointcut missing `routeFrom`; `GlobalExceptionHandler`
shadowing 400, then separately 405/415; `ToolDriftDetector` ignoring trailing JSON, then a
second fix for that FIRST fix breaking its own key-reordering guarantee; a missing `Allow`
header on 405s; `PartialRouteException`'s own try/catch missing response-parsing failures) plus
one claimed bug (bad `Accept` header -> 500) that a test proved doesn't actually reproduce in
this app's configuration -- not fixed, because there was nothing to fix. The items below were
real too, but each needed an actual design decision or a larger-blast-radius refactor rather
than a safe mechanical fix, so they were documented instead of rushed on the spot — consistent
with "do NOT invent these" below: an honest known-limitation beats a hasty fix that might be
subtly wrong. Three of these (mid-escalation cost loss, the TOCTOU race, observability
granularity) got their own proper design passes in a later part of this session and are now
fixed; see below.

- **No authentication or authorization anywhere in the app.** Not specific to any one endpoint — flagged because a second review pass singled out the new `GET /governance/spend` (admin-style cross-tenant dump) as a meaningful *increase* in exposure vs. the existing per-tenant `GET /governance/spend/{tenant}` (which at least requires already knowing a tenant name). True, and worth a second look before anything here is internet-facing, but it's a whole-app scope decision (add Spring Security, API keys, something) — not a targeted fix, and this is a demo/portfolio gateway, not a production one. Noting it plainly rather than bolting on partial auth for one endpoint.
- **DOWNGRADE lowers the floor, not a hard ceiling.** `GatewayService` forces `routeFrom(..., Tier.LUNA)` on a DOWNGRADE decision, but `SmartRouteService`'s escalation loop still climbs Luna→Terra→Sol if Luna's answer fails validation — a downgraded tenant under budget pressure can still end up billed at Sol rates. This is deliberate, not an oversight: hard-capping at Luna regardless of answer quality would violate this project's own quality-parity principle (see the eval gotchas above) by silently accepting bad answers to save money. Documented, not changed.
- **Budget check uses a single-attempt estimate; actual spend can still land higher.** `GatewayService.handle()` checks `BudgetGuard` against `classification.startTier().costUsd(...)` for ONE attempt, but real spend (the reconciliation delta) sums cost across every escalation attempt (the same per-attempt-cost gotcha above). A request that reserves comfortably under cap can, after escalating through all three tiers, true up to meaningfully more than was reserved — the TOCTOU fix below prevents OTHER concurrent requests from over-booking the tenant, but a single request's own multi-attempt cost still isn't knowable in advance. Pre-computing a worst-case estimate would require walking the full escalation chain before spending anything, which isn't possible (you don't know if Luna will pass until you call it).
- ~~Budget check and spend booking aren't atomic (TOCTOU race).~~ **RESOLVED** — `SpendLedger.reserveIfWithinCap` makes the admission decision and the reservation atomic in one `ConcurrentHashMap.compute()`; `GatewayService` reserves the estimate up front and reconciles to the actual cost afterward. See `governance-NOTES.md` for the full writeup. Verified with a real concurrency stress test (50 threads, 500 concurrent attempts against a cap that allows exactly 100) — confirmed meaningful by reverting to a naive implementation and watching it over-admit to 140 reproducibly across 3 runs, then confirming the fix holds at exactly 100 every time, including on the real CI runner (not just locally).
- ~~A model-call exception mid-escalation loses tracking of already-incurred cost.~~ **RESOLVED** — new `PartialRouteException` carries a `RouteResult` snapshot of whatever was accumulated before the failing attempt; `RouterTelemetryAspect` and `GatewayService` both catch it, record/book the partial cost, then rethrow so the failure still propagates as before. See `routing-NOTES.md` / `gateway-NOTES.md` for the full writeup. Verified by reverting the core fix and confirming 3 of 4 new tests fail without it.
- ~~Observability records once per `route()`/`routeFrom()` call, not once per actual model call.~~ **RESOLVED** — `RouteResult` now carries `List<AttemptRecord>` (one per real model call, with per-attempt tier/tokens/cost/latency); `RouterTelemetryAspect` iterates and records each one instead of one aggregate entry per outer request. See `observability-NOTES.md` (Gotcha #3) / `routing-NOTES.md` for the full writeup. Verified with a 3-tier-escalation test asserting exactly 3 telemetry entries with correct per-tier attribution, confirmed meaningful by reverting to aggregate-only recording and watching it fail (expected 3, got 1).

## What to measure before posting (do NOT invent these)
- Real routed vs. Sol-only $ on the task set (run `--eval`).
- How often the classifier's start tier was right (attempts == 1).
- The break-even: at what escalation rate does routing stop saving money?

## Post angle (draft seed — not published)
Lead: "GPT-5.6 dropped 3 days ago with 3 price tiers. I wired Spring AI to route across them and measured whether 'just use the cheapest that works' actually saves money." Deliver the per-attempt-cost gotcha + the parity caveat. Java/Spring AI implementation = almost nobody else's angle.
