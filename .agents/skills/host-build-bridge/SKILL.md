---
name: host-build-bridge
description: Delegate compilation and heavy build commands from a Linux VM (guest) to the host machine (Mac) to leverage native Apple Silicon hardware performance. Use when compiling, building, or testing inside a VM with a shared workspace.
---

# Host Build Bridge

Delegates heavy build tasks (Gradle, Cargo, npm, etc.) from an isolated **Linux VM** to the **Mac Host** to utilize native Apple Silicon CPU performance, while preserving strict directory-jailed sandbox security.

## Core Path Mapping

| Environment | Machine | Path Root | Script |
| :--- | :--- | :--- | :--- |
| **Host** | Mac (Host OS) | `~/.vm/.monet` | [`scripts/mac_build_daemon.py`](scripts/mac_build_daemon.py) |
| **Guest** | Linux (Lima VM) | `~/shared` | [`scripts/mac-run`](scripts/mac-run) |

The bridge operates in two modes:

---

## Mode 1: Daily Usage (Environment Configured)

When the Mac daemon is already running on the Mac host, use `mac-run` to execute commands natively on Mac from within `~/shared`:

```bash
# Check daemon status
curl -s http://host.lima.internal:9876/health

# Run native builds on Mac from inside Linux VM
cd ~/shared/repo/zafiro
mac-run ./gradlew assembleDebug
```

👉 **Detailed workflow, log streaming, and troubleshooting**: Read [`references/daily-usage.md`](references/daily-usage.md).

---

## Mode 2: Environment Setup (Initial Configuration)

When setting up on a new machine or when the host daemon is not yet installed:

1. **Mac Host**: Run [`scripts/mac_build_daemon.py`](scripts/mac_build_daemon.py) with Python 3.
2. **Linux VM**: Install [`scripts/mac-run`](scripts/mac-run) into `~/.local/bin/`.
3. **Verify**: Ensure `http://host.lima.internal:9876/health` returns status OK.

👉 **Complete step-by-step setup and security sandbox details**: Read [`references/environment-setup.md`](references/environment-setup.md).
