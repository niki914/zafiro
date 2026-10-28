---
name: host-command-bridge
description: Delegate heavy compilation/testing or opening visual artifacts on the host Mac from the Linux VM. Keep all other tasks inside the VM.
---

# Host Command Bridge

Delegates heavy compilation tasks or visual presentation from the **Linux VM** to the **Mac Host**.

## Boundaries (MUST FOLLOW)

- **Allowed**:
  - Heavy compilation or test tasks (e.g. `mac-run ./gradlew assembleDebug`, `mac-run ./gradlew testDebugUnitTest`).
  - Presenting visual files/artifacts to the user on Mac (e.g. `mac-run open path/to/report.html`).
- **Forbidden**:
  - All routine tasks (git, file inspection, search, normal CLI commands) MUST run locally in this Lima VM. Minimize `mac-run` usage.
  - Strictly forbidden from reading or writing host data outside `~/.vm/.monet` (`~/shared`).

## Usage

Check if the daemon is reachable:

```bash
curl -s http://host.lima.internal:9876/health
```

Run commands from within any project directory under `~/shared`:

```bash
mac-run ./gradlew assembleDebug
mac-run open doc/report.html
```

## Daemon Troubleshooting

The Mac host runs the HTTP daemon server; the Linux VM is only a client (`mac-run`). If health check fails or connection is refused:

**Do NOT attempt to start the server inside the Linux VM.** Prompt the user to start the daemon on their Mac host:

```bash
# On Mac Host (run by user):
python3 ~/.vm/mac_command_daemon.py
```

If setting up for the first time on Mac:
```bash
# On Mac Host (run by user):
mkdir -p ~/.vm
cp ~/.vm/.monet/repo/zafiro/.agents/skills/host-command-bridge/scripts/mac_command_daemon.py ~/.vm/mac_command_daemon.py
python3 ~/.vm/mac_command_daemon.py
```

To ensure `mac-run` is available in the Linux VM:
```bash
# In Linux VM:
mkdir -p ~/.local/bin
cp ~/shared/repo/zafiro/.agents/skills/host-command-bridge/scripts/mac-run ~/.local/bin/mac-run
chmod +x ~/.local/bin/mac-run
```

## References

- [`references/daily-usage.md`](references/daily-usage.md): Whitelist CLI management and detailed usage.
- [`references/environment-setup.md`](references/environment-setup.md): Complete setup guide, persistent launchd service, and security sandbox architecture.
