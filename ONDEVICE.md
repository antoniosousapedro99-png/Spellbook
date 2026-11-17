# Running Cybernomicon's Local AI (ELI5)

How to run the local AI (Ollama) that powers the Familiar and the agent
planner — either on a computer the phone talks to, or **entirely on the phone
itself**. Nothing here uses the cloud.

## The mental model

Three pieces talk in a chain: **App → Hub → Ollama → model.**

1. **Ollama** — the engine that runs an AI model locally (a local "ChatGPT
   server"). Listens on `http://localhost:11434`.
2. **A model** (e.g. `llama3.2:3b`) — the brain Ollama loads. Downloaded once.
3. **Daemon Hub** — the Cybernomicon backend. It asks Ollama questions; the
   phone app talks to the Hub.

---

## PATH A — Ollama on a computer (easiest to start)

Do this on a laptop/desktop/Raspberry Pi on the **same Wi‑Fi** as your phone.

**1. Install Ollama**
- macOS/Windows: download from <https://ollama.com> and open it (runs in the
  background).
- Linux: `curl -fsSL https://ollama.com/install.sh | sh`

**2. Download a model** (one time, ~2–5 GB): `ollama pull llama3.2:3b`

**3. Make sure Ollama runs** — the Mac/Windows app already does; on Linux run
`ollama serve`.

**4. Start the Hub pointed at Ollama** (in `daemon-hub/`):
```bash
export CYBERNOMICON_PLANNER=local
export CYBERNOMICON_OLLAMA_MODEL=llama3.2:3b
export CYBERNOMICON_HOST=0.0.0.0        # so the phone can reach it
python -m cybernomicon.main             # prints an API token — copy it
```
Or just: `CYBERNOMICON_HOST=0.0.0.0 ./scripts/start-hub.sh` (it starts Ollama
and pulls the model for you — see below).

**5. Connect the phone** — find the computer's IP (`ip addr` / `ipconfig` /
System Settings → Network), e.g. `192.168.1.50`. In the app's **Grimoire** tab:
Hub URL `http://192.168.1.50:8787`, paste the token, **Link**. (For plain
`http` to a real device, enable the cleartext opt-in in
`network_security_config.xml`, or use TLS — see `LOCAL_SETUP.md`.)

**6. Check** — the **Familiar** tab shows `🜂 llama3.2:3b`. Ask it to scan.

---

## PATH B — Fully on the phone (no computer)

We run a tiny Linux *inside* your phone with **Termux**, put Ollama + the Hub
there, and the app talks to `127.0.0.1` (the phone talking to itself).

⚠️ **Pick a model by your phone's RAM** (and keep ~6–8 GB storage free):

| Phone RAM | Model | Pull command |
|-----------|-------|--------------|
| 8 GB+ | llama3.2:3b (best) | `ollama pull llama3.2:3b` |
| 6 GB | qwen2.5:3b | `ollama pull qwen2.5:3b` |
| 4 GB | llama3.2:1b (basic) | `ollama pull llama3.2:1b` |

Bigger = smarter but slower/heavier. 3B is the sweet spot; 1B works but is
weaker at the structured tool format the Familiar uses.

**1. Install Termux** from **F‑Droid** (<https://f-droid.org>) — *not* the Play
Store version (it's broken).

**2. Set up a clean Linux inside Termux:**
```bash
pkg update && pkg upgrade -y
pkg install proot-distro -y
proot-distro install debian
proot-distro login debian
```
(Every time you reopen Termux, run `proot-distro login debian` to get back in.)

**3. Install Ollama + tools inside it:**
```bash
apt update && apt install -y curl python3 python3-pip python3-venv git
curl -fsSL https://ollama.com/install.sh | sh
```

**4. Start Ollama and pull a model:**
```bash
ollama serve &
ollama pull llama3.2:3b
```

**5. Get Cybernomicon and launch the Hub:**
```bash
git clone https://github.com/antoniosousapedro99-png/Spellbook
cd Spellbook/daemon-hub
pip install -r requirements.txt --break-system-packages
export CYBERNOMICON_OLLAMA_MODEL=llama3.2:3b
./scripts/start-hub.sh          # starts Ollama if needed, pulls model, runs hub
```
Copy the **API token** it prints.

**6. Open the Cybernomicon app** on the same phone. Hub URL
`http://127.0.0.1:8787` (already allowed — no TLS needed), paste the token,
**Link**. The Familiar shows `🜂 llama3.2:3b`. Everything now runs on the phone.

**Later, to start it again:** open Termux →
```bash
proot-distro login debian
cd Spellbook/daemon-hub && ./scripts/start-hub.sh
```

---

## The `start-hub.sh` helper

`daemon-hub/scripts/start-hub.sh` does the boring parts for you:
1. starts `ollama serve` in the background if it isn't already running,
2. waits for Ollama to answer,
3. pulls the configured model if it's missing,
4. starts the Daemon Hub with the local planner enabled.

Environment overrides (all optional):

| Variable | Default | Meaning |
|----------|---------|---------|
| `CYBERNOMICON_OLLAMA_MODEL` | `llama3.2:3b` | which model to use/pull |
| `CYBERNOMICON_OLLAMA_URL` | `http://localhost:11434` | where Ollama listens |
| `CYBERNOMICON_PLANNER` | `local` | `local` or `deterministic` |
| `CYBERNOMICON_HOST` | `127.0.0.1` | set `0.0.0.0` to expose on the LAN |

---

## Fully on-device AI (Cyberdeck app, Phase 3)

The **Cyberdeck** app can run the Familiar's AI on-device with **no hub and no
Ollama**, via MediaPipe (Gemma). This is separate from the Termux path above —
it's all inside the app.

1. In the app: **Grimoire** → Engine toggle **on-device** → **Power on**.
2. Get a MediaPipe-format model file (a Gemma CPU build, e.g.
   `gemma-2b-it-cpu-int4.bin`, ~1.3 GB) from the official MediaPipe / LiteRT
   model pages. It must match the MediaPipe LLM Inference format.
3. Put the file where the app can read it. The most reliable way is to push it
   into the app's own files directory (no storage permission needed), which is
   also the default path the app shows:
   ```bash
   adb push gemma-2b-it-cpu-int4.bin \
     /sdcard/Android/data/com.cybernomicon.grimoire/files/model.bin
   ```
4. In **Grimoire → On-device AI**, confirm the path and tap **Load model**.
   When it shows `🜂 loaded`, open **Familiar** and chat — it's running on-device.

⚠️ **Honest caveats:** a 2B model needs well over 1 GB of free RAM to run; on a
phone with little free memory it may fail to load or be very slow. Model/format
compatibility with the MediaPipe version can be finicky. If on-device AI won't
run on your phone, just flip the Engine toggle to **remote hub** and use the
Ollama-backed Familiar — scanning still works fully on-device either way. (A
built-in model file-picker is a planned improvement over the `adb push` step.)

## Caveats
- On‑phone models are **slower** (a few seconds per reply on a 3B) — normal.
- If `pip install --break-system-packages` errors or `pydantic` won't build,
  make a venv instead: `python3 -m venv .venv && . .venv/bin/activate && pip
  install -r requirements.txt`.
- These on‑device steps are the best‑known‑good recipe but aren't tested on
  every device; if a step errors, keep the exact message — it's usually a
  one‑line fix.
- Only scan networks you're authorized to test.
