# SmartRoute — documentation

Start with the [main README](../README.md) for the what and why. This folder holds the deeper
engineering material.

## Design & rationale
- **[ENGINEERING.md](ENGINEERING.md)** — design rationale, the Spring AI gotchas the docs don't
  mention, the honest per-attempt cost model, design decisions & known limitations, and how
  everything here is verified.

## Per-module design notes
- **[routing-NOTES.md](routing-NOTES.md)** — the classifier + tier-escalation core.
- **[governance-NOTES.md](governance-NOTES.md)** — per-tenant budgets and the atomic
  check-and-reserve that closes the TOCTOU race.
- **[guardrails-NOTES.md](guardrails-NOTES.md)** — prompt-injection scanning and tool-drift
  detection.
- **[observability-NOTES.md](observability-NOTES.md)** — per-call cost/latency/tier telemetry.
- **[gateway-NOTES.md](gateway-NOTES.md)** — how the layers compose into one gateway pass.

## Results & writing
- **[simulation-results.md](simulation-results.md)** — a credit-free routing cost projection on
  real published GPT-5.6 pricing (clearly labeled as a projection, not a live measurement).
- **[blog/](blog/)** — a technical writeup on treating GPT-5.6's price tiers as a routing problem.
