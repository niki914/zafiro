# Environment Setup Guide

Use this guide when setting up host-delegated builds from scratch on a new machine or environment.

---

## 1. Directory Mapping & Architecture

| Machine | Role | Directory Root | Notes |
| :--- | :--- | :--- | :--- |
| **Mac Host** | Execution Host | `~/.vm/.monet` | Apple Silicon native JDK/SDK/toolchains |
| **Linux VM** | Guest / Workspace | `~/shared` | Mounted via Lima virtiofs |

Every directory inside `~/shared` on Linux corresponds 1:1 to `~/.vm/.monet` on Mac.

---

## 2. Setup Step 1: Mac Host Daemon

The daemon runs on Mac, accepts build commands, and executes them strictly inside `~/.vm/.monet`.

### 1) Copy the daemon script to Mac
Copy `scripts/mac_build_daemon.py` to your Mac:
```bash
# On Mac Host:
mkdir -p ~/.vm
cp ~/.vm/.monet/repo/zafiro/.agents/skills/host-build-bridge/scripts/mac_build_daemon.py ~/.vm/mac_build_daemon.py
chmod +x ~/.vm/mac_build_daemon.py
```

### 2) Run the daemon on Mac
Run using macOS built-in Python 3:
```bash
# On Mac Host:
python3 ~/.vm/mac_build_daemon.py
```

Expected terminal output:
```text
============================================================
 Mac Build Daemon is running on port 9876
 Jailed directory: /Users/<user>/.vm/.monet
 Press Ctrl+C to stop.
============================================================
```

> [!TIP]
> Run the daemon in a dedicated Mac terminal tab, a `tmux` session, or create a macOS `launchd` plist for auto-start.

---

## 3. Setup Step 2: Linux VM Client (`mac-run`)

The client CLI runs inside the Linux VM, resolves the project path relative to `~/shared`, and streams build outputs from Mac.

### 1) Install `mac-run`
```bash
# Inside Linux VM:
mkdir -p ~/.local/bin
cp ~/shared/repo/zafiro/.agents/skills/host-build-bridge/scripts/mac-run ~/.local/bin/mac-run
chmod +x ~/.local/bin/mac-run
```

### 2) Verify PATH
Ensure `~/.local/bin` is in your `PATH` (typically defined in `~/.bashrc` or `~/.profile`):
```bash
which mac-run
```

---

## 4. Setup Step 3: Network & Health Check

Test network connectivity from inside the Linux VM:

```bash
# Inside Linux VM:
curl -s http://host.lima.internal:9876/health
```

If successful, you will receive:
```json
{
  "status": "ok",
  "message": "Mac Build Daemon is running",
  "base_dir": "..."
}
```

Now you can test a dummy execution:
```bash
cd ~/shared/repo/zafiro
mac-run ./gradlew --version
```

---

## 5. Security & Sandbox Boundary

The bridge is designed with strict isolation guarantees:

1. **Jailed Root Directory**: The Mac daemon enforces `target_dir.relative_to(BASE_DIR)`. Any request attempting path traversal (`../`) outside `~/.vm/.monet` is rejected with `HTTP 403 Forbidden`.
2. **No Host SSH Required**: Does not require enabling macOS Remote Login or generating SSH keys with host shell access.
3. **No Root Privilege**: The daemon runs entirely with normal user permissions on Mac.
