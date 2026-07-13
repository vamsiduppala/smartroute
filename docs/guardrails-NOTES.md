# guardrails module — build notes

**Rides:** Vercel AI SDK 7 `fingerprintTools` / `detectToolDrift` (2026-07-09). This is a **Java port of that defense**, not the AI SDK itself — stated plainly so no false claim reaches engineers.

**What it does:**
- `ToolDriftDetector` — SHA-256 fingerprints an MCP tool's schema when you `trust()` it; `hasDrifted()` flags a later schema that no longer matches ("rug pull" defense). Unknown tools are not treated as drift.
- `PromptInjectionScanner` — regex heuristics for override/exfiltration patterns ("ignore previous instructions", "leak the api key", jailbreak markers); returns a score + the matched fragments.

**Endpoints:** `POST /guardrails/scan`, `POST /guardrails/tools/trust`, `POST /guardrails/tools/check`, `GET /guardrails/tools/{name}/trusted` (wires up `ToolDriftDetector.isTrusted()`, which existed with zero callers until this).
**Tests:** identical-schema→no drift, changed-schema→drift, untrusted→not drift, key-reordering→no drift, key-reordering-with-trailing-content→still no drift, trailing-content-appended→drift, trailing-whitespace-only→no drift; benign→clean, injection→flagged, null→clean. Pure logic, offline.

**Gotcha #1 (resolved):** fingerprint the *canonical* schema — if the MCP server reorders JSON keys between calls, naive hashing false-positives. Fixed: `canonicalize()` parses the JSON and sorts object keys recursively (Jackson `ORDER_MAP_ENTRIES_BY_KEYS`) before hashing; non-JSON falls back to the trimmed raw string. Covered by `keyReorderingIsNotDrift`.

**Gotcha #2 (resolved 2026-07-13):** plain `readValue()` silently DISCARDS anything after a valid JSON value — `"{\"a\":1} appended-content"` canonicalized identically to `"{\"a\":1}"`, so a schema altered only by appending trailing content defeated `hasDrifted()` entirely, the exact "rug pull" this class exists to catch.

**Gotcha #3 (resolved 2026-07-13, same day, caught by a second review pass on gotcha #2's own fix):** the first fix for gotcha #2 enabled `FAIL_ON_TRAILING_TOKENS`, which throws on trailing content — but that exception fell into the SAME catch-all used for genuinely-non-JSON input (raw trimmed-string comparison, which IS key-order sensitive), silently reintroducing gotcha #1 as soon as trailing content was involved. The actual fix: parse just the leading JSON value (canonicalizing it normally — key-reordering stays safe), find the raw remainder via the parser's own consumed-character offset, and append that remainder (trimmed) to what gets hashed. Both properties — key-reordering safety AND trailing-content detection — now hold simultaneously. See `ToolDriftDetector.canonicalize()`'s own javadoc for the full history.
