# Running Cybernomicon Locally

Cybernomicon is **local-first**. The Daemon Hub, database, Spell Engine, spells
and the SCRYER daemon all run on your own hardware with **no cloud dependency**.
The Android app talks only to *your* hub. The only component that could ever
reach the internet is the optional Phase III LLM planner — and that can be local
too (see [Local AI](#local-ai-optional)).

This guide covers the three ways to run it and the networking details that
actually trip people up.

---

## Pick a topology

```
 (A) Hub on a computer, app on emulator      ← easiest to try
 (B) Hub on a computer, app on a real phone   ← best for real lab work
 (C) Hub + everything on the phone (Termux)   ← fully on-device, more setup
```

| | Where the hub runs | Where the app runs | Hub URL in the app |
|---|---|---|---|
| **A** | your computer | Android emulator | `http://10.0.2.2:8787` *(default)* |
| **B** | your computer / Raspberry Pi | physical device | `https://<computer-LAN-IP>:8787` |
| **C** | the phone itself (Termux) | same phone | `http://127.0.0.1:8787` |

**Recommendation:** a computer or Raspberry Pi on your lab Wi-Fi makes a far
better scanning vantage point than a phone, so **B** (or **A** to experiment) is
what you want for real reconnaissance.

---

## Step 1 — Run the Daemon Hub

```bash
cd daemon-hub
python -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt
pytest -q                      # optional: 29 tests
python -m cybernomicon.main    # prints the API token on first run
```

By default it binds to `127.0.0.1` (local only). To let other devices on your
LAN reach it (topology B), bind to all interfaces:

```bash
export CYBERNOMICON_HOST=0.0.0.0
export CYBERNOMICON_PORT=8787
python -m cybernomicon.main
```

Find your computer's LAN IP: `ip addr` (Linux) / `ipconfig` (Windows) /
`ifconfig` (macOS) — something like `192.168.1.50`.

### Enable TLS for a real device (recommended for topology B)

```bash
./scripts/gen_lab_cert.sh
export CYBERNOMICON_TLS_CERT=$PWD/var/tls/cert.pem
export CYBERNOMICON_TLS_KEY=$PWD/var/tls/key.pem
export CYBERNOMICON_HOST=0.0.0.0
python -m cybernomicon.main    # now serves https://
```

Then install `var/tls/cert.pem` on the phone (Settings → Security → *Install a
certificate* → CA certificate). The app verifies against the system trust store
— it never disables verification in code.

> **Plain-HTTP shortcut (lab only):** if you don't want TLS on a trusted lab
> network, you can instead uncomment your hub's LAN IP in
> `android-client/app/src/main/res/xml/network_security_config.xml` and use an
> `http://` URL. Use this only on a network you trust.

---

## Step 2 — Run the Android app

The Android SDK is required to build the APK; it is **not** buildable in a
headless container.

### Option A — Emulator (no phone needed)
1. Install [Android Studio](https://developer.android.com/studio) (bundles the
   SDK).
2. `cp android-client/local.properties.example android-client/local.properties`
   and set `sdk.dir`.
3. Open `android-client/` in Android Studio, let Gradle sync, pick an emulator
   (AVD) and **Run**.
4. The emulator reaches your computer's hub at `10.0.2.2:8787` — already the
   app's default. In the **Grimoire** tab, paste the API token and tap **Link**.

### Option B — Physical device
1. Build: `cd android-client && ./gradlew assembleDebug`
   → APK at `app/build/outputs/apk/debug/app-debug.apk`.
2. Install it (USB with `adb install …`, or copy + sideload).
3. In **Grimoire**, set the Hub URL to your computer's address
   (`https://192.168.1.50:8787` with TLS, or the http opt-in above) + token,
   then **Link**.

Command-line only (no IDE)? You still need the SDK's command-line tools; set
`sdk.dir` in `local.properties`, then `./gradlew assembleDebug`.

---

## Topology C — Everything on the phone (Termux)

Runs the hub *on the phone itself*, so the whole platform is on-device.

```bash
# in Termux
pkg update && pkg install python rust binutils
pip install fastapi "uvicorn[standard]" pydantic jsonschema
# (rust is needed to build pydantic-core on Android; this step is the slow one)

git clone <your-repo> && cd Spellbook/daemon-hub
python -m cybernomicon.main
```

Install the app on the same phone and point it at `http://127.0.0.1:8787`
(already allowed for cleartext). Note Termux builds take a while because of the
Rust compile; if that's painful, use topology A/B with the hub on a computer.

---

## Local AI (optional)

Today's SCRYER uses a **deterministic planner** — no LLM, nothing leaves your
machine. When you want autonomous natural-language planning (Phase III), the
`Planner` seam supports a local model so you still avoid the cloud:

```bash
# on the hub machine
# install Ollama from https://ollama.com, then:
ollama pull llama3.1        # or qwen2.5, mistral, etc.
ollama serve                # serves http://localhost:11434
```

The planned config will be `CYBERNOMICON_PLANNER=local` +
`CYBERNOMICON_OLLAMA_URL=http://localhost:11434`. (The Ollama-backed planner is
not wired yet — ask and it's a small, self-contained addition behind the
existing interface.)

---

## Authorization reminder

No network spell runs until you authorize a scope (a CIDR you are allowed to
test), and every target is checked against it. Authorize from the app's
**Familiar**/**Grimoire** flow or directly:

```bash
curl -s -X POST http://127.0.0.1:8787/api/targets \
     -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
     -d '{"cidr":"192.168.1.0/24","label":"home-lab"}'
```

Only scan networks you own or have written permission to assess.
