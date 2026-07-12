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
- Compiles clean on Spring AI 1.0.0 GA + JDK 21 (11 classes).
- 6/6 unit tests green: `Tier` cost math + escalation chain, `ComplexityClassifier` tier buckets.
- NOT yet run end-to-end: the live GPT-5.6 eval needs `OPENAI_API_KEY`. Real cost numbers pending that run.

## What to measure before posting (do NOT invent these)
- Real routed vs. Sol-only $ on the task set (run `--eval`).
- How often the classifier's start tier was right (attempts == 1).
- The break-even: at what escalation rate does routing stop saving money?

## Post angle (draft seed — not published)
Lead: "GPT-5.6 dropped 3 days ago with 3 price tiers. I wired Spring AI to route across them and measured whether 'just use the cheapest that works' actually saves money." Deliver the per-attempt-cost gotcha + the parity caveat. Java/Spring AI implementation = almost nobody else's angle.
