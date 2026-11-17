# Cyberdeck — Build Guide (Windows 11)

Follow these top to bottom once, and you'll have a complete, **isolated** hacking
lab for **lawful** security testing on systems you own: vulnerable targets +
a full Kali Linux attacker box, all on your machine, nothing exposed to your
network.

> **Scope rule (non-negotiable):** everything here is for targets you **own or
> are explicitly authorized to test** — this lab, your own devices, your own
> network. Never aim these tools at anyone else's systems.

## What you'll have at the end
- **Targets:** DVWA, OWASP Juice Shop, OWASP WebGoat (Docker, localhost-only).
- **Attacker:** Kali Linux in WSL, with nmap / sqlmap / hashcat / john / hydra /
  gobuster / nikto, etc. — no separate VM needed.

---

## 0. Prerequisites  (you've already done these ✓)
- **WSL2:** `wsl --install` in an *Administrator* PowerShell, then reboot.
- **Docker Desktop:** installed and showing **"Engine running."**
  Verify: `docker run hello-world` prints "Hello from Docker!".

## 1. Start the lab (the targets)
In PowerShell, from the extracted project folder (the one containing `lab`):
```powershell
docker compose -f lab/docker-compose.yml up -d
```
Verify in a browser: **http://127.0.0.1:8081** loads DVWA.
(Targets: DVWA `:8081`, Juice Shop `:8082`, WebGoat `:8083/WebGoat`.)

## 2. Install the attacker — Kali Linux in WSL
In an **Administrator** PowerShell:
```powershell
wsl --install -d kali-linux
```
Set a username + password when prompted. That's a real Kali environment — no VM.

Open Kali any time: Start menu → **Kali**, or in PowerShell: `wsl -d kali-linux`.

## 3. Install the toolkit (inside Kali)
In the Kali shell, paste this one line:
```bash
sudo apt-get update && sudo apt-get install -y \
  nmap netcat-traditional curl wget dnsutils sqlmap nikto gobuster \
  hydra john hashcat whatweb wordlists seclists git
```
Then unpack the famous `rockyou` wordlist (used for cracking practice):
```bash
sudo gunzip -k /usr/share/wordlists/rockyou.txt.gz 2>/dev/null; ls -la /usr/share/wordlists/rockyou.txt
```
(Optional, large: `sudo apt-get install -y metasploit-framework` for Metasploit.)

## 4. Connect the attacker to the lab
Docker publishes the targets on your machine's localhost, which Kali-in-WSL can
reach too. Test it:
```bash
curl -s -o /dev/null -w "DVWA responds: %{http_code}\n" http://localhost:8081
nmap -sV -p 8081,8082,8083 localhost
```
You should see an HTTP code (302/200) and nmap listing the three services.
*If `localhost` doesn't reach them,* find the Windows host IP and use that:
```bash
ip route | grep default        # the IP after 'via' is your Windows host
```

## 5. First end-to-end attack (proves the whole deck works)
1. **Extract credentials with SQL injection** (browser): open DVWA → log in
   `admin`/`password` → **Create/Reset Database** → **DVWA Security = Low** →
   **SQL Injection** → in the User ID box enter:
   ```
   1' UNION SELECT user, password FROM users #
   ```
   You now have every username + **MD5 password hash**.
2. **Crack those hashes** (Kali) — these are your lab's own hashes, so this is
   the lawful way to practise credential cracking. Save the hashes to a file,
   then:
   ```bash
   # hashes.txt = one MD5 per line (e.g. 5f4dcc3b5aa765d61d8327deb882cf99)
   john --format=raw-md5 --wordlist=/usr/share/wordlists/rockyou.txt hashes.txt
   john --show --format=raw-md5 hashes.txt
   # or with hashcat:
   hashcat -m 0 -a 0 hashes.txt /usr/share/wordlists/rockyou.txt
   ```
   You'll recover the plaintext passwords. That's the full chain: **find →
   exploit → extract → crack**, end to end, entirely inside your own lab.

---

## Daily use (after setup)
```powershell
# Windows: start / stop the targets
docker compose -f lab/docker-compose.yml up -d
docker compose -f lab/docker-compose.yml down
```
```bash
# open the attacker box
wsl -d kali-linux
```

## Where to go next
- Work the web targets: DVWA (all modules on Low→Med→High), Juice Shop
  challenges, WebGoat lessons.
- Add full-OS targets (Metasploitable 2 / VulnHub) as VirtualBox VMs on a
  **host-only** network when you want network/host exploitation + Metasploit.
- Structured learning: PortSwigger Web Security Academy (free), TryHackMe,
  Hack The Box. See `lab/README.md`.
