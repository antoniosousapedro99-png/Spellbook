# Changelog

All notable changes to Project Cybernomicon are recorded here.

## [Unreleased] — Phase III (local agent intelligence)

### Added — on-device AI Familiar (Phase 3)
- On-device LLM via **MediaPipe LLM Inference** (`tasks-genai`): `LlmEngine`
  interface + `MediaPipeLlm` (loads a Gemma-style model file from a device path)
  + `LocalFamiliar`, a Kotlin agentic chat loop (JSON decisions, tools
  summon_scryer/list_devices/list_findings, lenient parsing, bounded). Wired
  into `LocalBackend.chat()`; `supportsChat` turns true once a model is loaded.
- Grimoire gains an **On-device AI** card (model path + Load, with status/errors);
  the ViewModel is now an `AndroidViewModel` and loads the model off the main
  thread. With a model loaded, the Familiar runs fully on-device — no Ollama.
- 4 JVM unit tests for the Familiar loop with a stub LLM (plain reply, JSON
  extraction from prose, real tool run through the engine, unknown-tool
  containment). *Actual model inference is untested here — it only runs on a
  device, and a 2B model needs more RAM than some phones have free.*

### Changed — Cyberdeck rebrand + native engine wired in (Phase 2)
- The Android app is renamed **Cyberdeck** (launcher label + title bar) with a
  cyberpunk theme pass: near-black voids, neon violet/cyan/magenta, and a
  monospaced "deck" typography for headers and labels.
- **Native engine wired into the UI.** A `Backend` interface abstracts the data
  source; `RemoteBackend` wraps the Python hub, `LocalBackend` runs the on-device
  Kotlin engine. An **Engine** toggle in Grimoire switches between **on-device**
  (default — scanning with no hub/Termux) and **remote hub**. Scanning, daemons,
  VEIL, telemetry and the Astral Plane all work locally; the AI Familiar still
  needs the remote hub until Phase 3. On-device actions synthesize live-feed
  events so the Chronicle stays live without a WebSocket.

### Added — native engine (Phase 1 of the all-in-one app)
- A pure-Kotlin on-device engine under `android-client/.../engine/`, porting the
  core from Python so scanning can run entirely inside the app (no Termux, no
  hub): `Authorization` (IPv4 CIDR scope boundary), `VeilCore` (intensity, rate
  limiting, inter-op spacing, kill-switch, impact/telemetry), the `SpellEngine`
  (VEIL gate → timeout → telemetry), native `scry`/`identify`/`archive` spells
  (coroutines + java.net sockets), the `Scryer` daemon, topology inference, an
  in-memory `EngineStore`, and a `LocalEngine` facade mirroring the hub API.
- 10 JVM unit tests (run on the build host, no device needed) covering
  authorization/expand, VEIL presets, topology gateway inference, SCRY/IDENTIFY
  against a real localhost listener, the Scryer daemon end-to-end, and the
  emergency-stop halt/resume path. (Phase 2 wires the UI + Room; Phase 3 adds
  the on-device LLM Familiar to remove Ollama.)

### Added — persist device results on direct cast
- `record_spell_effects()` persists scry/identify results on a direct cast (the
  cast endpoint calls it), so tap-casting Scry fills the Astral Plane — not just
  summoning the Scryer daemon.

### Added — tap-to-cast spells
- Android: spell cards in Grimoire are now tappable — a cast dialog builds a
  parameter form from the spell's input schema (required fields marked, lists
  comma-separated) and runs it directly via `POST /api/spells/{id}/cast`,
  bypassing the Familiar. The result status shows inline, and devices /
  topology / findings / telemetry refresh after. (Casts still pass through VEIL
  and target authorization — a scan outside an authorized scope returns denied.)

### Added — VEIL dashboard
- `GET /api/veil/telemetry` now returns a rollup `summary` (op count, counts by
  visibility, total noise) alongside the rows.
- Android: the **Chronicle** tab is now a VEIL dashboard — a detection-telemetry
  summary (low/medium/high visibility tallies, total noise, active intensity),
  a per-operation list, and a **live activity feed** streamed over the
  `/ws/activity` WebSocket (first use of the WebSocket in the client), plus the
  findings list. A live/offline indicator shows the socket state.

### Added — VEIL Core (v0.5 veil-first architecture)
- **VEIL Core** (`cybernomicon/veil/`): a mandatory control layer the Spell
  Engine is built around, so every operation — manual, AI-driven, or ritual —
  passes through it. Responsibilities: target-scope verification, execution
  policy (operational intensity), rate limiting + resource caps, operational-
  impact assessment, telemetry, detection assessment, audit, and an
  **emergency kill-switch** (blocks new ops, cancels in-flight ones; ops finish
  with status `halted`).
- Operational intensity `quiet`|`normal`|`active` (default `quiet`,
  `CYBERNOMICON_VEIL_INTENSITY`) shapes our own traffic (concurrency +
  inter-op spacing); SCRY honours it from the ExecutionContext.
- Per-operation **telemetry** (noise score + visibility vs the *target's*
  defenders — a coverage measurement, never evasion) in a new `telemetry`
  table. Daemons declare VEIL `capabilities` + `expected_telemetry`.
- Endpoints: `GET /api/veil/status`, `POST /api/veil/intensity`,
  `POST /api/veil/halt`, `POST /api/veil/resume`, `GET /api/veil/telemetry`.
- Android: a VEIL control card in Grimoire (intensity chips + a prominent
  **EMERGENCY STOP** / Resume), and VEIL state loaded on refresh.
- Design guarantees: VEIL never conceals activity from Cybernomicon's own
  audit; "detection assessment" measures visibility to the authorized target's
  defenses and implements no monitoring evasion.

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
