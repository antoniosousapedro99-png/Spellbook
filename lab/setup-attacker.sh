#!/usr/bin/env bash
# Install the attacker toolkit inside a Kali (or Debian/Ubuntu) WSL distro.
# For LAWFUL testing of systems you own / are authorized to test only.
#
#   wsl -d kali-linux        # open Kali
#   ./setup-attacker.sh      # run this
set -euo pipefail

echo "[cyberdeck] updating package lists…"
sudo apt-get update -y

echo "[cyberdeck] installing toolkit…"
sudo apt-get install -y \
  nmap netcat-traditional curl wget dnsutils \
  sqlmap nikto gobuster hydra john hashcat whatweb git || {
    echo "[cyberdeck] some packages failed — on non-Kali distros a few may have"
    echo "            different names. The core tools above cover most practice."
  }

# Wordlists (Kali ships these; harmless if absent).
sudo apt-get install -y wordlists seclists 2>/dev/null || true
if [ -f /usr/share/wordlists/rockyou.txt.gz ]; then
  sudo gunzip -k /usr/share/wordlists/rockyou.txt.gz 2>/dev/null || true
fi

echo
echo "[cyberdeck] installed tools:"
for t in nmap sqlmap hydra john hashcat gobuster nikto whatweb; do
  printf '  %-10s ' "$t"
  command -v "$t" >/dev/null 2>&1 && command -v "$t" || echo "(missing)"
done
echo
echo "[cyberdeck] reach the lab:  curl http://localhost:8081   nmap -sV -p 8081-8083 localhost"
echo "[cyberdeck] wordlist:       /usr/share/wordlists/rockyou.txt"
