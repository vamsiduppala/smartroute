# routing module — build notes

**Rides:** GPT-5.6 (Sol/Terra/Luna), launched 2026-07-09 — this is the core module the rest of the gateway wraps.

**What it does:**
- `ComplexityClassifier` — cheap, zero-model-call heuristic (length + regex hard-signals + code-fence detection) that picks a **starting** tier. It only needs to be roughly right, because escalation catches the misses.
- `SmartRouteService` — walks the tiers Luna → Terra → Sol, calling the model at each rung and checking the answer with a `Validator`, until one passes or Sol also fails. `route(prompt, validator)` uses the classifier's pick as the start; `routeFrom(prompt, validator, startTier)` forces a specific start tier instead (used by the gateway's governance layer — see `gateway-NOTES.md`).

**The gotcha that matters most (see BUILD_NOTES.md):** cost is billed **per attempt at that attempt's own rate**, not at the final tier's rate. A Luna attempt that fails and escalates to Terra costs Luna-rate + Terra-rate, not just Terra-rate. Routing is not free — a classifier that under-shoots on a hard task can cost *more* than going straight to Sol.

**Endpoints:** `POST /route` (routing alone, no guardrails/governance wrapping — see `GatewayController` / `POST /gateway/route` for the full pass).
**Tests:** `SmartRouteServiceTest` — pass-at-start-tier, escalate-one-tier (with the per-attempt-cost assertion), climb-to-Sol-without-looping-forever, and `routeFrom` overriding the classifier. `ComplexityClassifierTest` covers the tier-selection heuristic. All offline: `OpenAiChatModel` is mocked, `ChatResponse`/`Generation`/`AssistantMessage` are constructed as real objects (their accessors are `final` — Mockito can't stub them).
