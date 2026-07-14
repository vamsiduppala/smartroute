---
title: "\"Just use the cheapest model that works\" — does routing GPT-5.6's price tiers actually save money?"
draft: true
tags: [spring-ai, java, llm, cost-optimization, gpt-5.6]
---

# "Just use the cheapest model that works" — does routing GPT-5.6's price tiers actually save money?

GPT-5.6 shipped as **three tiers**, not one model: Luna, Terra, and Sol, with a **5× price spread**
($1/$6 per Mtok at the bottom, $5/$30 at the top). The moment a model family has tiers, "which model
should I call?" stops being a config value and becomes a **routing decision** — one you can automate.

So I built [SmartRoute](https://github.com/vamsiduppala/smartroute): a small Spring Boot gateway that
sends each request to the *cheapest* tier likely to handle it, escalates to a stronger tier only when
the answer fails a check, and — the part I actually cared about — **measures whether that saves money
at equal quality**, instead of assuming it does.

Here's what I learned.

## The idea, and the trap inside it

The naive pitch writes itself: classify the prompt, start cheap, escalate on failure, pocket the
difference. But there's a trap that a lot of "LLM router" demos quietly skip over:

> **Routing is not free.** If you try Luna, it fails, and you escalate to Sol, you paid for **Luna
> *and* Sol**. When the classifier under-shoots on a genuinely hard prompt, routing can cost *more*
> than just calling Sol in the first place.

This completely changes how you have to account for cost. The tempting shortcut is to bill each request
at the tier that finally answered it. That **understates** real spend, because it silently forgets every
failed-then-escalated attempt you already paid for. So SmartRoute accumulates cost **per attempt at that
attempt's own rate**:

```java
// each attempt is billed at ITS tier's price, then summed — not billed at the final tier
totalCost += tier.costUsd(inputTokens, outputTokens);
```

If your router doesn't do this, its "savings" number is fiction.

## Spring AI made the routing itself almost trivial

GPT-5.6 rides the OpenAI-compatible API, so there's no custom SDK — the Spring AI OpenAI starter targets
the new model ids directly. The single most useful thing I found: **you don't need three beans for three
tiers.** You override the model *per request* on the `Prompt`:

```java
var options = OpenAiChatOptions.builder().model(tier.modelId).build(); // gpt-5.6-luna / -terra / -sol
chatModel.call(new Prompt(prompt, options));
```

That one override is the entire mechanism behind multi-tier routing in Spring AI. The router becomes a
loop: classify → call the starting tier → if a `Validator` rejects the answer, escalate one rung and try
again, up to Sol.

```
prompt ──▶ ComplexityClassifier ──▶ start tier ──▶ call GPT-5.6 tier
                                                       │
                                       pass? ──yes──▶ return (answer + real cost)
                                         │no
                                         ▼
                                    escalate one rung (Luna → Terra → Sol)
```

The classifier is deliberately dumb — a cheap regex/length heuristic with **zero model calls**. It only
has to be *roughly* right, because escalation catches its misses. Spending a model call to decide which
model to call would defeat the whole point.

## A couple of Spring AI gotchas the docs don't mention

- **The starter got renamed at 1.0 GA.** Most tutorials still say `spring-ai-openai-spring-boot-starter`.
  On Spring AI **1.0.0 GA** it's `spring-ai-starter-model-openai`, and the old id won't resolve against
  the BOM. I only caught it by actually compiling.
- **Token-usage getters drifted between versions** (Integer vs. Long on `Usage.getPromptTokens()`), so I
  normalize through `Number` rather than casting to a fixed type. Pin your Spring AI version.

## Measuring it honestly

This is the part most "I built an LLM router" posts skip. A savings percentage means nothing on its own:

> **Savings are only real at quality parity.** If the routed path saves 50% but passes fewer tasks than
> always-using-Sol, you haven't saved money — you've lowered quality and relabeled it. So the benchmark
> reports **routed-pass vs. baseline-pass side by side**, every time.

On a mixed 14-task sample workload, a credit-free simulation on real published pricing projected **~54% cost
reduction at an equal 14/14 pass rate**. I'm careful to label that a *projection*, not a live measurement —
because I don't have a billed key to run the real thing yet, and pretending a simulation is a live number
would be exactly the dishonesty the parity caveat is about.

The three numbers actually worth measuring, once you have live access:

1. **Real routed vs. Sol-only spend** on a fixed task set.
2. **How often the classifier's first pick was right** (attempts == 1).
3. **The break-even escalation rate** — at what failure rate does routing stop saving money at all?

## What surprised me

The interesting engineering wasn't the routing — Spring AI made that five lines. It was everything
*around* it: honest cost accounting across attempts, gating savings on quality, and making the whole
thing runnable and testable without a live key (the repo ships a `demo` profile that runs the full
pipeline — guardrails, budgets, routing, telemetry — with a stubbed model and no API key).

Tiered model families are going to make this a common pattern. If you build one, the router is the easy
half. The honest measurement is the half worth getting right.

---

*Code, architecture notes, and the full cost model:
[github.com/vamsiduppala/smartroute](https://github.com/vamsiduppala/smartroute). Built with Java 21 +
Spring Boot + Spring AI.*
