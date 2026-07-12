# SmartRoute — a GPT-5.6 tier router on Spring AI

**Rides:** OpenAI **GPT-5.6** (Sol / Terra / Luna), launched **2026-07-09** → [launch coverage](https://techcrunch.com/2026/07/09/openai-launches-its-new-family-of-models-with-gpt-5-6/) · [OpenAI release notes](https://openai.com/products/release-notes/)

GPT-5.6 shipped as three tiers with a 5× price spread (Luna $1/$6 → Sol $5/$30 per Mtok). Reaching for Sol on every call is the expensive default. **SmartRoute classifies each request and sends it to the cheapest tier that still passes a check, escalating only on failure** — built with **Spring AI** so it drops into a Java/Spring stack.

```
prompt ──▶ ComplexityClassifier ──▶ start tier ──▶ call GPT-5.6 tier
                                                        │
                                        pass? ──yes──▶ return (answer + cost)
                                          │no
                                          ▼
                                     escalate one tier (Luna→Terra→Sol)
```

## Why Spring AI
GPT-5.6 is served on the OpenAI-compatible API, so the OpenAI starter targets the new model ids directly — no new SDK. The router overrides the model **per request**:

```java
var options = OpenAiChatOptions.builder().model(tier.modelId).build(); // gpt-5.6-luna / -terra / -sol
chatModel.call(new Prompt(prompt, options));
```

## Enterprise modules (AI Gateway)
SmartRoute is growing into an **AI Gateway** — a Spring Boot control plane in front of every LLM call. Each module rides a specific dated launch:

| Module | Endpoint | Rides | Status |
|--------|----------|-------|--------|
| **routing** (core) | `POST /route` | GPT-5.6 tiers (2026-07-09) | ✅ tested |
| **governance** | `GET/PUT /governance/*` | GPT-5.6 tier pricing | ✅ tested |
| **guardrails** | `POST /guardrails/*` | AI SDK 7 tool-drift defense (2026-07-09), Java port | ✅ tested |
| **observability** | `GET /observability/metrics` | AI SDK 7 telemetry redesign (2026-07-09) | ✅ tested |
| rag / memory / longcontext | — | Anthropic web-search / agent-memory / Sonnet 5 1M | ⏳ designed (needs Anthropic key) |

20 unit tests across the modules, all green. See `docs/*-NOTES.md` and `architecture` for design.

## Run it
Prerequisites: **Java 21** and **Maven 3.9+**.
```bash
export OPENAI_API_KEY=sk-...
mvn spring-boot:run                                      # serves POST /route
curl -s localhost:8080/route -H 'content-type: application/json' \
     -d '{"prompt":"What is the capital of France?"}'    # → answered by Luna, fractions of a cent

mvn spring-boot:run "-Dspring-boot.run.arguments=--eval --spring.main.web-application-type=none"  # benchmark → eval/results.md
```
(The benchmark needs an OpenAI key **with billing enabled** — a key without quota returns HTTP 429 `insufficient_quota`.)

## Benchmark
`eval/tasks.jsonl` holds a fixed task set spanning trivial → hard. The runner answers each **twice** — always-Sol baseline vs. routed — and writes `eval/results.md`.

> **Numbers are generated at run time by `EvalRunner`; nothing here is hardcoded.** Run the eval with your key to populate `eval/results.md`. (This section is intentionally empty until then — see BUILD_NOTES.md for why routing is not always a win.)

## Disclosure
Built with AI assistance (Claude). Model pricing/ids reflect the GPT-5.6 launch on 2026-07-09; verify against OpenAI's release notes before relying on them.

## License
MIT
