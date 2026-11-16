#!/usr/bin/env bash
# Launch the Cybernomicon Daemon Hub.
#
# Environment:
#   CYBERNOMICON_HOST          bind host (default 127.0.0.1)
#   CYBERNOMICON_PORT          bind port (default 8787)
#   CYBERNOMICON_API_TOKEN     fixed token (otherwise generated on first run)
#   CYBERNOMICON_TLS_CERT/KEY  enable TLS (recommended beyond localhost)
#   CYBERNOMICON_DATA_DIR      state dir (default daemon-hub/var)
set -euo pipefail
cd "$(dirname "$0")/.."
exec python -m cybernomicon.main
