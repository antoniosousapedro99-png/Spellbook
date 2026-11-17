# CLAUDE.md — Cyberdeck / Cybernomicon

Project context for Claude Code. Read this first when resuming work.

## What this is
**Cyberdeck** (internal codename Cybernomicon) is an extensible, agentic network
**security-research platform for AUTHORIZED testing only**. Capabilities are
*spells*, autonomous agents are *daemons*, workflows are *rituals*. An occult/
cyberpunk theme is **interface aesthetic only** — never let it drive technical
decisions.

## ⚖️ Scope boundary (a hard design invariant — do not remove)
Every network operation must resolve its target against an **explicitly
authorized scope** (a CIDR the operator declares). This authorization gate is
enforced centrally (`authorization.py` on the backend, `engine/Net.kt` on the
app) and is load-bearing, not optional. **Do not add features that bypass or
disable it** (e.g. "scan any network", credential cracking of third-party
systems, auth-bypass tooling). The platform is for networks/devices the operator
**owns or is authorized to test**; offensive practice belongs in the self-owned
lab (`lab/`). Keep internal audit complete; "detection assessment" measures a
test's visibility to the *target's* defenders, it does not evade monitoring.

## Repository layout
```
daemon-hub/      Python backend (FastAPI): Spell Engine, VEIL, daemons, spells,
                 Familiar, SQLite. Fully tested with pytest.
android-client/  Cyberdeck Android app (Kotlin + Jetpack Compose). Includes a
                 native on-device engine (engine/) mirroring the hub, the Forge
                 (Rhino JS scriptable spells), and an optional on-device LLM.
lab/             Self-owned practice lab: docker-compose targets + CYBERDECK.md
                 build guide + attacker toolkit installer. LAWFUL practice only.
dist/            Prebuilt debug APK (cyberdeck.apk).
docs/            Design notes (e.g. SPELL_AUTHORING.md).
*.md             README, ARCHITECTURE, CHANGELOG, LOCAL_SETUP, ONDEVICE.
```

## Build / test / run

### Daemon Hub (Python 3.11+)
```bash
cd daemon-hub
python -m venv .venv && . .venv/bin/activate   # Windows: .venv\Scripts\activate
pip install -r requirements.txt
pytest -q                                       # ~60 tests, must stay green
python -m cybernomicon.main                     # runs the hub (prints API token)
```
Local-AI (optional): install Ollama, `ollama pull llama3.2:3b`, then
`CYBERNOMICON_PLANNER=local ./scripts/start-hub.sh`. See `ONDEVICE.md`.

### Android app (needs the Android SDK)
```bash
cd android-client
cp local.properties.example local.properties    # set sdk.dir
./gradlew testDebugUnitTest                      # JVM unit tests (engine + Forge)
./gradlew assembleDebug                          # -> app/build/outputs/apk/debug/
```
A prebuilt APK is at `dist/cyberdeck.apk`. The native on-device engine lives in
`app/src/main/java/com/cybernomicon/grimoire/engine/` and is JVM-unit-tested
(`app/src/test/...`) — no device needed to verify it.

### Practice lab (needs Docker)
```bash
docker compose -f lab/docker-compose.yml up -d   # DVWA/Juice Shop/WebGoat, localhost-only
docker compose -f lab/docker-compose.yml down
```
Full build-your-cyberdeck guide (Kali-in-WSL attacker + first attack chain):
**`lab/CYBERDECK.md`**.

## Architecture highlights
- **VEIL Core** — mandatory control layer every spell passes through: target
  authorization, operational intensity (rate limiting), telemetry, and an
  emergency kill-switch. Backend: `cybernomicon/veil/`; app: `engine/Veil.kt`.
- **Spell Engine** — fixed pipeline: permit → validate → VEIL gate → run(timeout)
  → telemetry → record. Spells only supply the handler body.
- **Daemons** — `Scryer` (recon) via a `Planner` seam; deterministic by default,
  LLM planner optional.
- **Familiar** — conversational agent (remote Ollama, or on-device MediaPipe).
- **Forge** — user-authored JavaScript spells (Rhino), sandboxed and
  authorization-gated. Backend engine package + app `engine/ScriptSpell.kt`.

## Conventions
- Keep tests green (pytest for the hub; `testDebugUnitTest` for the app engine).
- Pure logic is unit-tested on the JVM/host; device-only paths (MediaPipe
  inference, Android UI) are clearly flagged as untested-here.
- `main` holds the working state. Prebuilt APK is refreshed in `dist/`.
