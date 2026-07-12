# guardrails module — build notes

**Rides:** Vercel AI SDK 7 `fingerprintTools` / `detectToolDrift` (2026-07-09). This is a **Java port of that defense**, not the AI SDK itself — stated plainly so no false claim reaches engineers.

**What it does:**
- `ToolDriftDetector` — SHA-256 fingerprints an MCP tool's schema when you `trust()` it; `hasDrifted()` flags a later schema that no longer matches ("rug pull" defense). Unknown tools are not treated as drift.
- `PromptInjectionScanner` — regex heuristics for override/exfiltration patterns ("ignore previous instructions", "leak the api key", jailbreak markers); returns a score + the matched fragments.

**Endpoints:** `POST /guardrails/scan`, `POST /guardrails/tools/trust`, `POST /guardrails/tools/check`.
**Tests:** identical-schema→no drift, changed-schema→drift, untrusted→not drift; benign→clean, injection→flagged, null→clean. Pure logic, offline.

**Gotcha (resolved):** fingerprint the *canonical* schema — if the MCP server reorders JSON keys between calls, naive hashing false-positives. Fixed: `canonicalize()` parses the JSON and sorts object keys recursively (Jackson `ORDER_MAP_ENTRIES_BY_KEYS`) before hashing; non-JSON falls back to the trimmed raw string. Covered by `keyReorderingIsNotDrift`.
