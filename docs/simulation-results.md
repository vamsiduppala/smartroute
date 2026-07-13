# SmartRoute routing simulation (NOT a live API benchmark)

Deterministic stub model + REAL published GPT-5.6 pricing (`Tier.costUsd`). Zero credits used.
Live API numbers would require an OpenAI account with billing enabled.

| task requires | routed ended at | attempts | Sol-only $ | routed $ | pass |
|---------------|-----------------|----------|-----------|----------|------|
| LUNA | LUNA | 1 | $0.000125 | $0.000025 | PASS |
| LUNA | LUNA | 1 | $0.000095 | $0.000019 | PASS |
| TERRA | TERRA | 1 | $0.000115 | $0.000057 | PASS |
| SOL | SOL | 1 | $0.000165 | $0.000165 | PASS |
| LUNA | LUNA | 1 | $0.000155 | $0.000031 | PASS |

**Always-Sol:** $0.000655  ·  **Routed:** $0.000298  ·  **Saved: 54.6%** at equal pass rate (5/5).
