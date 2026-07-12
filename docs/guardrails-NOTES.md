# guardrails module — build notes

**Rides:** Vercel AI SDK 7 `fingerprintTools` / `detectToolDrift` (2026-07-09). This is a **Java port of that defense**, not the AI SDK itself — stated plainly so no false claim reaches engineers.

**What it does:**
- `ToolDriftDetector` — SHA-256 fingerprints an MCP tool's schema when you `trust()` it; `hasDrifted()` flags a later schema that no longer matches ("rug pull" defense). Unknown tools are not treated as drift.
- `PromptInjectionScanner` — regex heuristics for override/exfiltration patterns ("ignore previous instructions", "leak the api key", jailbreak markers); returns a score + the matched fragments.

**Endpoints:** `POST /guardrails/scan`, `POST /guardrails/tools/trust`, `POST /guardrails/tools/check`.
**Tests:** identical-schema→no drift, changed-schema→drift, untrusted→not drift; benign→clean, injection→flagged, null→clean. Pure logic, offline.

**Gotcha:** fingerprint the *canonical* schema string — if the MCP server reorders JSON keys between calls, naive hashing false-positives. (Left as a hardening TODO: canonicalize before hashing.)
