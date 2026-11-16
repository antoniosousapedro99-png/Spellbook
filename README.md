# Cybernomicon

> An extensible, AI-powered digital grimoire for **authorized** network
> intelligence and security research.

Cybernomicon is an agentic platform, not a themed scanner. Capabilities are
**spells**, autonomous agents are **daemons**, and workflows (later) are
**rituals**. An Android **Grimoire** drives a Python **Daemon Hub** that runs
spells through a **Spell Engine** and orchestrates daemons.

This repository is at **Phase I — Foundation**, with a working end-to-end
vertical slice reaching into Phase II (the SCRYER daemon).

```
┌──────────────────────┐        HTTPS + WebSocket        ┌───────────────────────┐
│   Android Grimoire    │  ◀───────── bearer token ─────▶ │     Daemon Hub        │
│  (Kotlin / Compose)   │                                 │   (FastAPI / Python)  │
│                       │                                 │ ┌───────────────────┐ │
│ Grimoire · Astral     │                                 │ │   Spell Engine    │ │
│ Familiar · Rituals    │                                 │ │ registry·validate │ │
│ Chronicle             │                                 │ │ permit·timeout    │ │
└──────────────────────┘                                  │ └───────────────────┘ │
                                                           │  Daemons · SQLite     │
                                                           └───────────────────────┘
```

## ⚖️ Authorized use only

Cybernomicon operates **exclusively against networks and devices you are
explicitly authorized to test**. The hub enforces this: no network spell runs
until an authorized scope (a CIDR) is configured, and every target is checked
against that allowlist before a packet is sent. There is no "scan everything"
mode. Do not use this software against systems you do not own or have written
permission to assess.

## What works today

- **Daemon Hub** (FastAPI) with bearer-token auth and optional TLS.
- **Spell Engine**: registry, JSON-schema input/output validation, per-spell
  permissions, hard execution timeouts, full execution history.
- **Spells**: `scry` (host discovery), `identify` (banner fingerprinting),
  `archive` (Chronicle writes).
- **SCRYER daemon**: plans and runs recon end-to-end (scry → identify →
  archive) over an authorized scope.
- **Local AI planner** (optional): drive daemons with a natural-language
  objective using a local Ollama model — no cloud. See
  [LOCAL_SETUP.md](LOCAL_SETUP.md#local-ai--your-assistant-in-your-pocket).
- **Persistence** (SQLite): targets, spells, executions, devices, findings,
  audit log.
- **Live activity** over WebSocket.
- **Android client** scaffold: five sections (Grimoire, Astral Plane, Familiar,
  Rituals, Chronicle) wired to the hub API.
- **Security foundations**: central target authorization, untrusted-data
  fencing (prompt-injection defence), append-only audit log.

See [CHANGELOG.md](CHANGELOG.md) for the full Phase I delivery,
[ARCHITECTURE.md](ARCHITECTURE.md) for the design and roadmap, and
[LOCAL_SETUP.md](LOCAL_SETUP.md) for running everything locally (computer,
phone, or fully on-device via Termux) and where to run the Android app.
New to the local AI? [ONDEVICE.md](ONDEVICE.md) is a step-by-step ELI5 guide to
setting up Ollama — including running it **entirely on your phone**.

## Quick start — Daemon Hub

```bash
cd daemon-hub
python -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt

# run the tests
pytest -q

# start the hub (prints the generated API token on first run)
python -m cybernomicon.main
```

Then, in another shell, drive it directly (replace `$TOKEN`):

```bash
TOKEN=...   # printed at startup
BASE=http://127.0.0.1:8787
AUTH="Authorization: Bearer $TOKEN"

# 1. authorize a scope you are allowed to test
curl -s -X POST $BASE/api/targets -H "$AUTH" -H 'Content-Type: application/json' \
     -d '{"cidr":"192.168.1.0/24","label":"home-lab"}'

# 2. summon SCRYER against it
curl -s -X POST $BASE/api/daemons/scryer/summon -H "$AUTH" \
     -H 'Content-Type: application/json' \
     -d '{"objective":"inventory the lab","scope":"192.168.1.0/24"}'

# 3. read the results
curl -s $BASE/api/astral/devices    -H "$AUTH"
curl -s $BASE/api/chronicle/findings -H "$AUTH"
```

TLS for lab use:

```bash
./scripts/gen_lab_cert.sh
export CYBERNOMICON_TLS_CERT=$PWD/var/tls/cert.pem
export CYBERNOMICON_TLS_KEY=$PWD/var/tls/key.pem
python -m cybernomicon.main    # now serves https://
```

## Quick start — Android Grimoire

Requires Android Studio / the Android SDK (not buildable in a headless CI
container without the SDK).

```bash
cd android-client
cp local.properties.example local.properties   # set sdk.dir
./gradlew assembleDebug
```

In the app's **Grimoire** tab, enter the hub URL and API token, then **Link**.
From an emulator, the host machine's loopback is reached at `10.0.2.2`, so the
default hub URL `http://10.0.2.2:8787` maps to a hub running on your computer.
For a physical device, use the hub's LAN address over **https** with your lab
CA installed on the device.

## Repository layout

```
daemon-hub/        Python backend (FastAPI) — fully tested
  cybernomicon/
    spell_engine/  registry + executor (validate, permit, timeout, record)
    spells/        scry, identify, archive
    daemons/       base orchestration + scryer
    authorization.py  target allowlist (the security boundary)
    safety.py      untrusted-data handling / prompt-injection fence
    db.py          SQLite persistence
    main.py        FastAPI routes + WebSocket
  tests/           pytest suite (29 tests)
android-client/    Kotlin + Jetpack Compose scaffold (5 sections)
docs/              design notes
ARCHITECTURE.md    full architecture + roadmap
CHANGELOG.md       development changelog
```
