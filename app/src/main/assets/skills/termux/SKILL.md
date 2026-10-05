---
name: termux
description: Load this skill for anything involving Termux (com.termux) — first-time SSH setup, connecting to Termux, or a Termux connection that stopped working.
---

# Termux

Termux is a separate Android app. Zafiro cannot run anything inside it directly, so SSH is the only way in.

Connecting needs host, port, username and password. Those exist only in the user's Termux install, so the user has to supply them; until all four are known no Termux work is possible. Once known, record all of them — password included — with the memory tool.

Defaults of a normal Termux install: SSH is not installed out of the box, the server listens on `127.0.0.1:8022` (loopback is enough, no LAN IP or Wi-Fi involved), the username is what `whoami` prints and looks like `u0_a123`, password authentication is on by default and must stay on because Zafiro's SSH client cannot use a private key, and `sshd` is an ordinary process inside Termux that dies whenever Termux is killed — so a refused or timed-out connection usually just means Termux is no longer running.

## No memory of Termux yet (never configured)

Setup happens in Termux by the user: `passwd` needs a real terminal, and we cannot type into Termux's terminal view. Paste this block into the reply and ask the user to run it, then report whatever Termux prints, errors included:

```sh
pkg update -y && pkg install -y openssh
passwd
whoami
sshd
```

`pkg install` provides `sshd`, `passwd` sets the login password and never echoes it, `whoami` prints the username, `sshd` starts the server. If Termux is not installed at all, the user has to install it from F-Droid and open it once first — do not install it yourself.

When the user reports the username and password, save host `127.0.0.1`, port `8022` and both values to memory, then connect and confirm the login actually works before saying setup is complete. If something failed, ask for the exact error rather than guessing corrections.

## Memory already has the connection details

Connect with the remembered values. If it fails, ask the user:

- Connection refused or timed out — Termux is closed and `sshd` is gone. Ask whether Termux is running; if not, ask the user to open Termux and run `sshd`. DO NOT launch or operate anything on the device yourself unless the user asks for it.
- Authentication failed — username or password is wrong. Ask the user to confirm both, then update memory.
- Termux was changed or reinstalled — username and password are new; run the setup block again and replace the old memory entries rather than keeping both.

# HARD GATE

- DO NOT launch Termux unless the user asks you to 'launch'
- Memorize the username, password, host, and port and any possible lessons during the experience