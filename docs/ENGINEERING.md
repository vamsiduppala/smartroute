# Engineering notes

Design rationale, the non-obvious gotchas this project ran into, and how everything here is
verified. Per-module deep-dives live alongside this file in [`docs/`](.).

## The problem GPT-5.6 tiering creates

GPT-5.6 isn't one model — it's a three-tier family with distinct API ids (`gpt-5.6-luna`,
`gpt-5.6-terra`, `gpt-5.6-sol`) and a **5× price spread** (Luna $1/$6, Terra $2.5/$15, Sol
$5/$30 per Mtok). Sol is marketed as more token-efficient on coding, but that's a claim worth
*measuring*, not repeating. The tiering turns "which model should answer this?" into a **routing
problem** you can automate — which is what SmartRoute does.

## Design

1. **`Tier`** — an enum carrying each tier's model id, per-Mtok pricing, and `escalate()`.
2. **`ComplexityClassifier`** — picks a *starting* tier from the prompt with a cheap regex/length
   heuristic and **zero model calls**. It only has to be roughly right, because escalation catches
   its misses.
3. **`SmartRouteService`** — calls the starting tier, and walks up (Luna → Terra → Sol) until a
   `Validator` accepts the answer, accumulating cost per attempt along the way.
4. **The gateway layers** — guardrails (injection/tool-drift), governance (per-tenant budgets),
   and observability (per-call telemetry) wrap the core router.
5. **`EvalRunner`** — benchmarks routed vs. always-Sol on a fixed task set and writes a markdown
   report.

## Gotchas the docs don't tell you

- **The Spring AI starter was renamed at 1.0 GA.** Most tutorials and RC-era docs still say
  `spring-ai-openai-spring-boot-starter`; on Spring AI **1.0.0 GA** the id is
  `spring-ai-starter-model-openai`. The old one won't resolve against the BOM (`'version' … is
  missing`). Caught by actually compiling, not by reading docs.
- **You don't need three beans for three tiers.** `OpenAiChatOptions.builder().model(id)` on the
  `Prompt` overrides the `application.yml` default *per call*. That single override is the whole
  trick behind multi-tier routing in Spring AI.
- **Token-usage getters have drifted across Spring AI versions** (Integer vs. Long on
  `Usage.getPromptTokens()`), which is why the code normalizes via `Number` rather than casting to
  a fixed type. Pin your Spring AI version and don't assume the getter's return type.

## The cost model (honest accounting)

This is the headline the naive implementation gets wrong:

- **Routing is not free.** A failed Luna attempt *before* escalating to Sol means you paid for
  Luna **and** Sol. If the classifier under-shoots on a genuinely hard prompt, routing can cost
  *more* than going straight to Sol. Cost is therefore accumulated **per attempt at that attempt's
  own rate**, not at the final tier's rate — a "charge the final tier" model understates real spend.
- **Savings are only real at quality parity.** The benchmark reports routed-pass vs. baseline-pass
  side by side. A savings percentage is meaningless if the routed path passes fewer tasks, so both
  numbers are always reported together.

## Design decisions & known limitations

Deliberate trade-offs, documented rather than silently made:

- **DOWNGRADE lowers the floor, not a hard ceiling.** On a budget-pressure DOWNGRADE, the gateway
  forces the request to *start* at Luna — but the escalation loop can still climb if Luna's answer
  fails validation, so a downgraded tenant can still end up billed at a higher tier. This is
  intentional: hard-capping at Luna regardless of answer quality would violate the project's own
  quality-parity principle by silently accepting bad answers to save money.
- **The budget check uses a single-attempt estimate.** Admission reserves the *starting* tier's
  one-attempt cost, then reconciles to actual spend afterward. A request that reserves comfortably
  under cap can, after escalating through all three tiers, true up higher — because you can't know
  whether Luna will pass until you call it, a precise worst-case reservation isn't computable up
  front. The atomic reservation still prevents *concurrent* requests from over-booking the tenant
  (see [`governance-NOTES.md`](governance-NOTES.md)); it just can't predict a single request's own
  escalation.
- **No authentication/authorization.** This is a demo/portfolio gateway, not an internet-facing
  service. Adding auth (Spring Security, API keys) is a whole-app decision rather than a targeted
  change, so it's called out plainly instead of bolted on partially.

Several trickier issues *were* fixed rather than merely documented — mid-escalation cost loss on a
model failure (now surfaced via `PartialRouteException`), a check-then-act race in budget
reservation (now a single atomic `compute`), and per-call vs. per-request telemetry granularity.
Each has a write-up in its module's `*-NOTES.md`.

## How this is verified

The bar throughout: *run it and observe the behavior*, don't assert it.

- **88 tests, green on every push.** Unit tests, `@WebMvcTest` per-controller web slices, and
  end-to-end flows (allow / blocked / downgrade) through the real embedded server. CI runs
  `mvn package`, so the Spring Boot executable-jar repackage is exercised on every push, not just
  compilation.
- **Concurrency proven, not assumed.** The budget reservation is checked with a 50-thread /
  500-request stress test that reproducibly over-admits on a naive implementation and holds exactly
  at cap on the atomic one.
- **The executable jar is booted directly** (`java -jar …`), a different code path than
  `spring-boot:run`'s exploded classes, to confirm health probes, Swagger UI, and error handling
  behave identically to the dev-server.
- **Credit-free measurement.** Because a live GPT-5.6 benchmark needs a billed API key,
  `RoutingSimulationTest` exercises the full routing + escalation path against a deterministic stub
  on real published pricing and writes [`simulation-results.md`](simulation-results.md) — a clearly
  labeled projection, never presented as a live number.
- **Keyless end-to-end runs.** A `demo` Spring profile injects a stubbed `ChatModel` (this is why
  the app depends on the `ChatModel` interface, not the concrete OpenAI client), so the whole
  routing + gateway pipeline — guardrails, budgets, telemetry — runs and can be exercised with no
  API key. It makes the project trivial to evaluate: clone, `./mvnw spring-boot:run
  -Dspring-boot.run.profiles=demo`, and curl the endpoints.
- **Kubernetes manifests are schema-validated** against the Kubernetes 1.31 OpenAPI schemas.

## Dependency hygiene

Dependencies are pinned to the latest patch within their major.minor line and kept current against
published advisories — Spring AI, Spring Boot's managed Jackson/Tomcat/Spring Framework versions,
and AspectJ were each checked and overridden to patched releases where the managed BOM lagged.
Major-version jumps (Spring Boot 4.x, Spring AI 2.x, springdoc 3.x) are intentionally *not* taken
here: each is a real migration with breaking changes, warranting its own focused effort rather than
a drive-by bump.

## Roadmap / deferred by design

- **rag / memory / longcontext modules.** These genuinely need live model calls to demonstrate
  anything real (retrieval quality, true long-context behavior); a stubbed version would be hollow
  scaffolding, so they're on the roadmap rather than faked.
- **Container build verification.** The `Dockerfile` passes `hadolint` static linting; building and
  smoke-running the actual image is the remaining step, pending an environment with a Docker daemon.
