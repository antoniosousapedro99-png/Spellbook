# Changelog

All notable changes to Project Cybernomicon are recorded here.

## [Unreleased] — Phase III (local agent intelligence)

### Added
- **Astral Plane topology**: `cybernomicon/topology.py` derives a network graph
  from discovered devices — grouping by subnet (/24, /64) and inferring the
  likely gateway (host at network+1), with device service roles. Exposed at
  `GET /api/astral/topology`. The Android Astral Plane tab now renders this as
  an interactive node-graph (deterministic radial layout, tap-to-inspect,
  scrollable) instead of a flat list. Relationships are clearly *inferred*, not
  measured — a future TRACE spell can replace inference with real hop data.
- **Conversational Familiar**: a chat agent over the local model with memory
  across turns. Each turn it either replies or calls a safe tool
  (`summon_scryer`, `list_devices`, `list_findings`); tool results are fenced
  as untrusted before being fed back. Conversations + messages are persisted
  (new `conversations`/`messages` tables). Progress is emitted live to the
  event bus (`familiar.thinking`/`thought`/`tool`/`tool_result`/`reply`).
  Endpoints: `POST /api/familiar/chat`, `GET /api/familiar/conversations`,
  `GET /api/familiar/conversations/{id}`.
- Android: the **Familiar** tab is now a chat screen (bubbles, tool-call trace,
  auto-scroll, live "working…" state), and **Grimoire** gained an authorized-
  scope management card (the app previously had no UI to authorize targets).
- **Local LLM planner (Ollama)** behind the existing `Planner` seam. Set
  `CYBERNOMICON_PLANNER=local` and a daemon reasons about a natural-language
  objective and chooses which spells to cast — entirely on local hardware, no
  cloud. Config: `CYBERNOMICON_OLLAMA_URL`, `CYBERNOMICON_OLLAMA_MODEL`,
  `CYBERNOMICON_LLM_TIMEOUT`, `CYBERNOMICON_LLM_MAX_STEPS`.
- `cybernomicon/llm/ollama.py`: minimal async Ollama client (JSON-mode chat +
  availability probe; bypasses the outbound proxy since Ollama is local).
- `GET /api/familiar/planner`: reports the active planner and, for local,
  Ollama reachability + installed models.
- Android **Familiar** tab shows local-AI status (`🜂 Local AI ready — <model>`).

### Changed
- The model only ever selects a **structured spell call** (never shell); every
  call still passes through the Spell Engine (validation, permissions, target
  authorization), so the model cannot exceed the daemon's grant.
- Spell results fed back to the model are wrapped in the untrusted-data fence
  (prompt-injection defence) before the model sees them.
- **Device persistence moved from `ScryerPlanner` into the `Scryer` daemon**
  (`on_result` hook) so it is planner-independent — the deterministic and LLM
  planners behave identically. A planner/Ollama failure now ends a run with a
  finding instead of crashing.
- `httpx` promoted to a runtime dependency (used by the Ollama client).

### Tests
- +6 tests (now 35): Ollama client parsing/availability, LLM planner step
  building, bounding, unknown-spell rejection, a mock-Ollama-driven SCRYER
  end-to-end, and the planner-status endpoint. No real model required.

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
