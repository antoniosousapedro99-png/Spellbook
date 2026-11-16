#!/usr/bin/env bash
# Generate a self-signed TLS certificate for LAB / local use only.
# For anything real, use a certificate from your own CA.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p var/tls
openssl req -x509 -newkey rsa:2048 -nodes \
    -keyout var/tls/key.pem -out var/tls/cert.pem \
    -days 365 -subj "/CN=cybernomicon-daemon-hub"
echo "Generated var/tls/{cert,key}.pem"
echo "Run with:"
echo "  export CYBERNOMICON_TLS_CERT=\$PWD/var/tls/cert.pem"
echo "  export CYBERNOMICON_TLS_KEY=\$PWD/var/tls/key.pem"
