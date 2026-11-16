# Changelog

All notable changes to Project Cybernomicon are recorded here.

## [0.1.0] — Phase I Foundation — 2026-11-16

First foundational release: a working, tested vertical slice.

### Daemon Hub (Python / FastAPI)
- Project structure, packaging (`pyproject.toml`, `requirements.txt`), run and
  TLS-cert scripts.
- **Spell Engine**: `SpellRegistry`, the `@spell` decorator + `Spell` contract,
  and an executor enforcing a fixed pipeline — permission check, JSON-schema
  input validation, execution recording, hard timeout, output validation,
  finalization, and live event emission.
- **Spells**: `scry` (unprivileged async TCP-connect host discovery),
  `identify` (service-banner fingerprinting, treating banners as untrusted),
  `archive` (Chronicle writes).
- **SCRYER daemon** with a deterministic planner (scry → identify → archive)
  behind a `Planner` seam for the future LLM planner.
- **Persistence** (SQLite): targets, spells, executions, devices, findings,
  audit log, behind a `Database` facade.
- **REST + WebSocket API** with bearer-token auth (constant-time) and optional
  TLS; `/health` open, everything else authenticated.

### Security
- Central **target authorization** boundary: CIDR allowlist, deny-by-default,
  per-address checks injected by the engine, capped scope expansion.
- Per-spell / per-daemon **permissions**.
- **Untrusted-data handling**: control-char scrubbing, length bounds, source
  tagging, and an LLM prompt fence — prompt-injection defence in place before
  the LLM.
- Append-only **audit log** of summons, spell starts, denials, and target
  changes.

### Android Grimoire (Kotlin / Jetpack Compose)
- Gradle project with wrapper; AGP 8.7 / Kotlin 2.0, Compose BOM.
- Occult/cyberpunk dark theme (violet primaries, cyan arcane accent).
- Five sections — Grimoire (connection + spell/daemon library), Astral Plane
  (devices), Familiar (summon a daemon with an objective), Rituals
  (placeholder), Chronicle (findings).
- Retrofit/OkHttp hub client with bearer auth and kotlinx.serialization;
  network-security config restricting cleartext to the lab loopback.
- *Note:* requires the Android SDK to build; it is not compiled in headless CI.

### Tests
- 29 pytest tests: authorization, spell-engine pipeline (success, validation,
  permissions, timeout, output validation, exception containment, recording),
  SCRY/IDENTIFY integration against a live loopback listener, SCRYER end-to-end,
  and HTTP-level API tests (auth required, discovery, authorize→summon,
  Chronicle).

### Fixed during development
- `@spell(into=…)` fell back to the global registry when the target registry
  was empty (an empty `SpellRegistry` is falsy); now checks `is not None`.
- `Database._execute` coerced dict (named-placeholder) params into a tuple,
  breaking named-parameter inserts; now preserves mappings.
- SCRYER did not persist IDENTIFY enrichment because the planner was only
  consulted when the step queue emptied; it is now consulted after every step.
