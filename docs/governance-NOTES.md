# governance module — build notes

**Rides:** GPT-5.6 tier pricing (Luna $1/$6 · Terra $2.5/$15 · Sol $5/$30 per Mtok), launched 2026-07-09.

**What it does:** per-tenant spend caps enforced *before* tokens are spent. `SpendLedger` tracks cumulative USD (priced via `Tier.costUsd()`); `BudgetGuard.evaluateAndReserve()` returns ALLOW / DOWNGRADE / REJECT and, unless REJECT, atomically reserves the estimate in the same step.

**Why DOWNGRADE exists (the interesting bit):** because GPT-5.6 has a 5x price spread, a call that would blow the budget on Sol doesn't have to be rejected — it can be *downgraded* to a cheaper tier and still often succeed. That three-way decision only makes sense because the tiers exist.

`BudgetGuard` only computes the ALLOW/DOWNGRADE/REJECT decision — it's `GatewayService` that actually acts on it (forces Luna on DOWNGRADE, blocks on REJECT). See `gateway-NOTES.md` for that wiring, including a bug where DOWNGRADE was computed correctly here but silently not enforced there.

**Gotcha (resolved 2026-07-13, method renamed from `evaluate` to `evaluateAndReserve`):** the original design was read-spent-then-later-write-actual-spend, a genuine TOCTOU race — two concurrent requests for the same tenant near their cap could both read the same pre-spend total, both get admitted, and both book spend afterward, letting cumulative spend exceed the cap under concurrent load. `SpendLedger`'s `ConcurrentHashMap` was always thread-safe per-operation, but that doesn't make a check-then-act *sequence* atomic — the classic mistake of assuming a thread-safe data structure makes the algorithm built on top of it thread-safe too. Fixed with `SpendLedger.reserveIfWithinCap`, which makes the decision AND the reservation atomic in one `compute()` call; `GatewayService` reconciles to the actual cost afterward via `ledger.add(tenant, actual - estimate)`. Proven with a real concurrency stress test (`SpendLedgerTest.reserveIfWithinCapNeverOverbooksUnderConcurrentLoad`) — 50 threads racing 500 total attempts against a cap that allows exactly 100 admissions; reverting to the naive implementation reproducibly over-admitted to 140.

**Endpoints:** `GET /governance/spend/{tenant}`, `GET /governance/spend` (all tenants — wires up `SpendLedger.snapshot()`, which existed with zero callers until this), `PUT /governance/budget/{tenant}?capUsd=` (rejects a negative cap with 400).
**Tests:** ALLOW/DOWNGRADE/REJECT boundaries + per-tenant cap override + concurrency stress test (`BudgetGuardTest`/`SpendLedgerTest`, pure logic, no API calls); web-layer validation and wiring (`GovernanceControllerTest`).
