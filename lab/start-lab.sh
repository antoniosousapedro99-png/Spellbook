#!/usr/bin/env bash
# Start / stop the Cyberdeck practice lab (intentionally-vulnerable targets,
# bound to localhost only). LAWFUL, self-owned practice — attack only the lab.
set -euo pipefail
cd "$(dirname "$0")"

cmd="${1:-up}"
case "$cmd" in
  up)
    docker compose up -d
    echo
    echo "Lab is up (localhost only):"
    echo "  DVWA        http://127.0.0.1:8081   (admin/password, then /setup.php)"
    echo "  Juice Shop  http://127.0.0.1:8082"
    echo "  WebGoat     http://127.0.0.1:8083/WebGoat"
    echo
    echo "Stop with:  ./start-lab.sh down"
    ;;
  down) docker compose down ;;
  status) docker compose ps ;;
  *) echo "usage: $0 [up|down|status]"; exit 1 ;;
esac
