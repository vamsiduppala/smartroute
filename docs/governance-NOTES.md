# governance module — build notes

**Rides:** GPT-5.6 tier pricing (Luna $1/$6 · Terra $2.5/$15 · Sol $5/$30 per Mtok), launched 2026-07-09.

**What it does:** per-tenant spend caps enforced *before* tokens are spent. `SpendLedger` tracks cumulative USD (priced via `Tier.costUsd()`); `BudgetGuard.evaluate()` returns ALLOW / DOWNGRADE / REJECT.

**Why DOWNGRADE exists (the interesting bit):** because GPT-5.6 has a 5x price spread, a call that would blow the budget on Sol doesn't have to be rejected — it can be *downgraded* to a cheaper tier and still often succeed. That three-way decision only makes sense because the tiers exist.

**Endpoints:** `GET /governance/spend/{tenant}`, `PUT /governance/budget/{tenant}?capUsd=`.
**Tests:** ALLOW/DOWNGRADE/REJECT boundaries + per-tenant cap override. No API calls; pure logic.
