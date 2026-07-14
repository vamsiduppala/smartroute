# SmartRoute routing simulation (NOT a live API benchmark)

Deterministic stub model + REAL published GPT-5.6 pricing (`Tier.costUsd`). Zero credits used.
Live API numbers would require an OpenAI account with billing enabled.

| task requires | routed ended at | attempts | Sol-only $ | routed $ | pass |
|---------------|-----------------|----------|-----------|----------|------|
| LUNA | LUNA | 1 | $0.000125 | $0.000025 | PASS |
| LUNA | LUNA | 1 | $0.000095 | $0.000019 | PASS |
| LUNA | LUNA | 1 | $0.000155 | $0.000031 | PASS |
| LUNA | LUNA | 1 | $0.000130 | $0.000026 | PASS |
| LUNA | LUNA | 1 | $0.000115 | $0.000023 | PASS |
| LUNA | LUNA | 1 | $0.000090 | $0.000018 | PASS |
| LUNA | LUNA | 1 | $0.000150 | $0.000030 | PASS |
| LUNA | LUNA | 1 | $0.000145 | $0.000029 | PASS |
| TERRA | TERRA | 1 | $0.000115 | $0.000057 | PASS |
| TERRA | TERRA | 2 | $0.000130 | $0.000091 | PASS |
| TERRA | TERRA | 2 | $0.000155 | $0.000109 | PASS |
| TERRA | TERRA | 1 | $0.000125 | $0.000063 | PASS |
| SOL | SOL | 1 | $0.000165 | $0.000165 | PASS |
| SOL | SOL | 1 | $0.000175 | $0.000175 | PASS |

**Always-Sol:** $0.001870  ·  **Routed:** $0.000860  ·  **Saved: 54.0%** at equal pass rate (14/14).
