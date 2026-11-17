# Cyberdeck Practice Lab

A self-owned, isolated environment for learning offensive security **lawfully** —
you attack only machines you own here. This is the right way to build real skills
(cracking, exploitation, web attacks, privilege escalation) without touching
anyone else's systems.

> **Golden rule:** everything in this lab is deliberately vulnerable. Keep it
> isolated — never expose these targets to your LAN or the internet, and only
> ever point your tools *at the lab*. Snapshot VMs before you break them.

---

## The two routes

- **A. Container lab (fastest, 2 minutes)** — Docker spins up vulnerable web
  apps on your machine, bound to localhost. Best for web-attack practice.
- **B. Full VM lab** — VirtualBox with a **Kali** attacker VM and vulnerable
  target VMs on a *host-only* network. Best for network/host attacks, cracking,
  Metasploit, etc.

Do both over time. Start with A to get hands-on today.

---

## A. Container lab (Docker)

Prereq: [Docker Desktop](https://www.docker.com/products/docker-desktop/) (or
Docker Engine on Linux).

```bash
docker compose -f lab/docker-compose.yml up -d     # start
docker compose -f lab/docker-compose.yml ps        # status
docker compose -f lab/docker-compose.yml down       # stop + remove
```

Targets (open in a browser on the **same machine**):

| Target | URL | Teaches |
|--------|-----|---------|
| **DVWA** | http://127.0.0.1:8081 (admin / password → run /setup.php) | SQLi, XSS, command injection, file upload, **login brute-force** (has a graded "Brute Force" module), CSRF — with Low/Med/High difficulty |
| **OWASP Juice Shop** | http://127.0.0.1:8082 | Modern web app: auth flaws, broken access control, injection, JWT, an in-app challenge scoreboard |
| **OWASP WebGoat** | http://127.0.0.1:8083/WebGoat | Guided lessons on each web vuln class with explanations |

All ports bind to `127.0.0.1`, so nothing is reachable from your Wi-Fi — the lab
stays on your machine.

---

## B. Full VM lab (VirtualBox + Kali + targets)

1. **Hypervisor:** install [VirtualBox](https://www.virtualbox.org/) (free).
2. **Attacker VM — Kali Linux:** download the VirtualBox image from
   <https://www.kali.org/get-kali/#kali-virtual-machines> and import it. Kali
   ships with the standard toolkit: `nmap`, Metasploit, `hashcat`, `john`,
   `hydra`, `aircrack-ng`, Burp Suite, `wireshark`, `sqlmap`, etc.
3. **Target VMs — pick any:**
   - **Metasploitable 2** — the classic vulnerable Linux box:
     <https://sourceforge.net/projects/metasploitable/> (login `msfadmin`/`msfadmin`).
   - **VulnHub** — hundreds of free vulnerable VMs: <https://www.vulnhub.com/>.
4. **Isolate the network (important):** in VirtualBox, set *both* the Kali and
   target VMs' network adapter to **Host-only Adapter** (File → Tools → Network
   Manager → create a host-only network first). This puts them on a private
   subnet (e.g. `192.168.56.0/24`) that can't reach your real LAN or the
   internet. Now Kali can attack the targets and nothing else.
5. **Snapshot** each target VM right after setup, so you can reset after you
   wreck it.

A typical first exercise from Kali against Metasploitable:
```bash
nmap -sV 192.168.56.0/24          # discover + fingerprint services
msfconsole                        # exploit a found service
hydra -l msfadmin -P wordlist ssh://<target>   # practise credential attacks (on YOUR lab)
```

---

## Guided, legal online labs (no setup)

If you'd rather learn against hosted, *authorized* targets:

- **PortSwigger Web Security Academy** — free, excellent: <https://portswigger.net/web-security>
- **TryHackMe** — guided rooms: <https://tryhackme.com/>
- **Hack The Box** — machines + academy: <https://www.hackthebox.com/>
- **PentesterLab**: <https://pentesterlab.com/>
- **OverTheWire** (wargames): <https://overthewire.org/wargames/>

These are explicitly provided for you to attack — that's what makes them legal.

---

## Using the lab with Cyberdeck

Because the lab is yours, you can point Cyberdeck at it legitimately:

1. Run the Daemon Hub (or the on-device engine) on the machine hosting the lab.
2. Authorize the lab scope — for the container lab that's `127.0.0.1/32`; for the
   VM lab it's the host-only subnet, e.g. `192.168.56.0/24`.
3. **Scry** it to map the targets, then write **Forge** spells to probe them —
   `connect()`, `banner()`, grade what you find. Real practice, real targets,
   all yours.

---

## What to actually practise (a path)

1. **Recon** — `nmap`/Scry the lab subnet; enumerate services and versions.
2. **Web** — DVWA + Juice Shop: SQLi, XSS, auth bypass, IDOR, brute-force logins.
3. **Exploitation** — Metasploit against Metasploitable services.
4. **Cracking** — practise `hashcat`/`john` on hashes **you generate yourself**
   (e.g. `mkpasswd`/`openssl` on a password you choose), and `hydra` against the
   lab's own login forms/SSH. This is where you learn credential attacks safely.
5. **Privilege escalation & persistence** — once you have a foothold on a lab box.

Keep notes, snapshot often, and only ever aim at the lab.
