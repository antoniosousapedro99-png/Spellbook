# Cybernomicon — Architecture

Status: **Phase I (Foundation)** complete, with a vertical slice reaching into
Phase II (SCRYER). This document records the design and the technical decisions
behind it, plus how the pieces are meant to grow through the roadmap.

## 1. Guiding principles

The spec sets three, and they drive every decision here:

1. **Intelligence over automation** — daemons *plan* against an objective; they
   do not run fixed scripts. In Phase I the planner is deterministic so the
   system is testable, but it sits behind a `Planner` interface that the
   Phase III LLM planner slots into without touching anything else.
2. **Modularity over monolith** — a spell is a self-contained capability with a
   declared contract; adding one is a new module, not a rewrite. Daemons compose
   spells; they never embed capability logic.
3. **Operator control over unrestricted autonomy** — targets, permissions and
   consequential actions are gated. The authorization boundary is central and
   cannot be bypassed by an individual spell.

## 2. Components

### Android Grimoire (`android-client/`)
Kotlin + Jetpack Compose, five sections (Grimoire, Astral Plane, Familiar,
Rituals, Chronicle) over a bottom nav. A single `AppViewModel` holds UI state
and talks to the hub through a Retrofit `HubApi`. Networking adds the bearer
token via an OkHttp interceptor; TLS uses the platform trust store (lab CAs are
installed on the device, never bypassed in code).

### Daemon Hub (`daemon-hub/cybernomicon/`)
FastAPI process exposing the REST + WebSocket API. Responsibilities: auth,
spell registry/discovery, spell execution (via the engine), daemon
orchestration, persistence, live events, audit.

### Spell Engine (`spell_engine/`)
The execution kernel. For every call it runs a fixed pipeline:

```
resolve → permit → validate(input) → record(running) → run(timeout)
        → validate(output) → finalize(status, result, duration) → emit
```

Individual spells only supply the `run` body. They cannot skip validation,
permission checks, timeouts, or recording. This is what makes "add a spell"
safe: the dangerous parts are not the spell author's responsibility.

### Agent Runtime (daemons)
`Daemon.run(objective)` is a bounded loop: ask the planner for a plan, execute
each step through the engine (with the daemon's permission set), interpret
results into findings, let the planner adapt, stop at `max_steps`. SCRYER's
planner is deterministic recon: `scry` the scope, `identify` each live host,
`archive` a summary. The seam for Phase III is exactly the `Planner` protocol.

### Network Research Layer (spells)
Pure-Python, unprivileged techniques only in Phase I: async TCP-connect
discovery (`scry`) and banner reads (`identify`). No nmap/raw sockets, which
keeps spells portable and sandboxable. Richer fingerprinting/enumeration is
Phase IV.

## 3. Security architecture

Security is structural, not bolted on:

- **Authentication** — bearer token on every `/api/*` call and the WebSocket;
  constant-time comparison. TLS at the transport (uvicorn `--ssl-*`).
- **Target authorization** (`authorization.py`) — the hard boundary. Network
  spells must `assert_authorized(address)` against a DB-backed CIDR allowlist.
  An empty allowlist denies everything; scope expansion is capped to prevent an
  accidental `/8` sweep. The `Authorization` object is *injected by the engine*,
  so a spell cannot widen its own scope.
- **Per-spell / per-daemon permissions** — spells declare required permissions;
  daemons carry a grant set; the engine enforces the intersection.
- **Untrusted-data handling** (`safety.py`) — banners, hostnames and other
  observations are scrubbed of control characters, length-bounded, tagged with
  their source, and fenced before they ever reach an LLM. Network data is data,
  never instructions. This is the prompt-injection defence the spec requires,
  in place before the LLM exists.
- **Audit log** — append-only record of summons, spell starts, denials, target
  authorization/revocation.
- **Resource limits** — per-spell timeouts; bounded daemon step count; bounded
  scope expansion; bounded event queues.

### Explicitly out of scope (by design)
No firewall bypass, credential attacks, persistence mechanisms, or arbitrary
remote command execution against third-party devices. Spells are structured
tool calls; there is no path from an agent to an arbitrary shell command.

## 4. Data model (SQLite)

| Table        | Purpose                                                        |
|--------------|----------------------------------------------------------------|
| `targets`    | Authorized scopes — the authorization boundary.                |
| `spells`     | Installed-spell metadata for discovery.                        |
| `executions` | Every spell run: params, status, result, timing.               |
| `devices`    | Discovered devices → the Astral Plane.                         |
| `findings`   | Interpreted observations/summaries → the Chronicle.            |
| `audit_log`  | Append-only consequential-action record.                       |

SQLite is deliberate: zero-ops, transactional, portable. Every query lives
behind the `Database` facade, so a later move to Postgres is contained.

## 5. API surface

| Method | Path                               | Purpose                         |
|--------|------------------------------------|---------------------------------|
| GET    | `/health`                          | Liveness (open).                |
| GET    | `/api/grimoire/spells`             | Spell discovery.                |
| GET    | `/api/grimoire/daemons`            | Daemon catalogue.               |
| POST   | `/api/spells/{id}/cast`            | Run a spell directly.           |
| GET    | `/api/executions`                  | Execution history.              |
| GET    | `/api/targets`                     | List authorized scopes.         |
| POST   | `/api/targets`                     | Authorize a scope.              |
| DELETE | `/api/targets/{id}`                | Revoke a scope.                 |
| POST   | `/api/daemons/{key}/summon`        | Run a daemon on an objective.   |
| GET    | `/api/astral/devices`              | Discovered devices.             |
| GET    | `/api/chronicle/findings`          | Findings history.               |
| GET    | `/api/chronicle/audit`             | Audit log.                      |
| WS     | `/ws/activity?token=…`             | Live events.                    |

All `/api/*` and the WebSocket require the bearer token.

## 6. Roadmap alignment

- **Phase I — Foundation** ✅ structure, hub, engine, registry/discovery, DB,
  authenticated comms. Done.
- **Phase II — SCRYER** ✅ (deterministic): discovery, structured results,
  persistence, follow-up via Familiar. Android *presentation* of results needs
  the SDK to build; the API and screens are in place.
- **Phase III — Agent intelligence**: replace `ScryerPlanner` with an
  LLM-backed `Planner` (structured tool-calling over the spell registry),
  task-state persistence, streamed progress, bounded adaptive execution.
- **Phase IV — Network intelligence**: richer `identify`, service enumeration,
  topology edges in the Astral Plane, config auditing (`inspect`/`ward`),
  reachability (`trace`), monitoring (`watch`).
- **Phase V — Spell library**: remaining spells, dynamic spell-package
  discovery/versioning, permission management UI.
- **Phase VI — Rituals**: multi-spell workflows with conditionals, scheduling,
  approval checkpoints.
- **Phase VII — Advanced**: more daemons (Watcher, Oracle, Invoker, Homing),
  authorized vulnerability validation, device integrations, reporting.

## 7. Key technical decisions

- **Pure-Python discovery** over shelling out to nmap: portability,
  sandboxability, no privileged capabilities, deterministic tests.
- **JSON Schema** for spell contracts: language-neutral, serializable to the
  client for form generation, validates both input and output.
- **Capabilities via `ExecutionContext`** (not globals): spells are testable in
  isolation and the security boundary stays centralized.
- **Deterministic planner first**: a fully testable end-to-end slice now, with a
  clean seam for LLM reasoning later — intelligence added without faking it.
- **Injected authorization**: the engine builds the `Authorization` from the DB
  per execution, so authorization cannot be spoofed by spell input.
