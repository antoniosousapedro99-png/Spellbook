#!/usr/bin/env bash
# One-command launch for the local-AI Daemon Hub (great for Termux/on-device).
#
# Does the boring parts:
#   1. start `ollama serve` in the background if it isn't already running
#   2. wait for Ollama to answer
#   3. pull the configured model if it's missing
#   4. start the Daemon Hub with the local planner enabled
#
# Overridable env vars (all optional):
#   CYBERNOMICON_OLLAMA_MODEL  (default llama3.2:3b)
#   CYBERNOMICON_OLLAMA_URL    (default http://localhost:11434)
#   CYBERNOMICON_PLANNER       (default local)
#   CYBERNOMICON_HOST          (default 127.0.0.1; set 0.0.0.0 to expose on LAN)
set -euo pipefail
cd "$(dirname "$0")/.."

export CYBERNOMICON_PLANNER="${CYBERNOMICON_PLANNER:-local}"
export CYBERNOMICON_OLLAMA_MODEL="${CYBERNOMICON_OLLAMA_MODEL:-llama3.2:3b}"
export CYBERNOMICON_OLLAMA_URL="${CYBERNOMICON_OLLAMA_URL:-http://localhost:11434}"

say() { printf '\033[35m[start-hub]\033[0m %s\n' "$*"; }

# 1 + 2. Ensure Ollama is up.
if curl -fsS "$CYBERNOMICON_OLLAMA_URL/api/tags" >/dev/null 2>&1; then
    say "Ollama already running at $CYBERNOMICON_OLLAMA_URL"
elif command -v ollama >/dev/null 2>&1; then
    say "starting 'ollama serve' in the background..."
    ollama serve >/tmp/cybernomicon-ollama.log 2>&1 &
    for _ in $(seq 1 30); do
        curl -fsS "$CYBERNOMICON_OLLAMA_URL/api/tags" >/dev/null 2>&1 && break
        sleep 1
    done
    if ! curl -fsS "$CYBERNOMICON_OLLAMA_URL/api/tags" >/dev/null 2>&1; then
        say "WARNING: Ollama did not come up; see /tmp/cybernomicon-ollama.log"
    else
        say "Ollama is up."
    fi
else
    say "WARNING: 'ollama' not installed and nothing is serving"
    say "         $CYBERNOMICON_OLLAMA_URL — the Familiar will report it's offline."
fi

# 3. Ensure the model is present (best effort).
if command -v ollama >/dev/null 2>&1; then
    base="${CYBERNOMICON_OLLAMA_MODEL%%:*}"
    if ! ollama list 2>/dev/null | grep -q "$base"; then
        say "pulling model '$CYBERNOMICON_OLLAMA_MODEL' (one-time download)..."
        ollama pull "$CYBERNOMICON_OLLAMA_MODEL" || \
            say "WARNING: could not pull $CYBERNOMICON_OLLAMA_MODEL"
    fi
fi

# 4. Launch the hub (pick python3 or python, whichever exists).
PY=python3; command -v python3 >/dev/null 2>&1 || PY=python
say "starting Daemon Hub (planner=$CYBERNOMICON_PLANNER, model=$CYBERNOMICON_OLLAMA_MODEL)"
exec "$PY" -m cybernomicon.main
