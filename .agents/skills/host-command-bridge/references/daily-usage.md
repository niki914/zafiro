# Daily Usage

Use this guide when the Mac command daemon is already running on the Mac host.

## 1. Quick Health Check

Verify that the host daemon is reachable from inside the Linux VM:

```bash
curl -s http://host.lima.internal:9876/health
```

Expected response:
```json
{
  "status": "ok",
  "message": "Mac Command Daemon is running",
  "base_dir": "~/.vm/.monet",
  "strict_mode": false,
  "whitelist_rules": {
    "exact_count": 0,
    "prefix_count": 0
  }
}
```

---

## 2. Running Commands via `mac-run`

Navigate to any project directory located under `~/shared` in the Linux VM:

### Builds & Tests
```bash
# In ~/shared/repo/zafiro
mac-run ./gradlew assembleDebug

# Run unit tests on Mac
mac-run ./gradlew testDebugUnitTest

# Cargo / Rust
mac-run cargo test
```

### Visual Artifact Presentation
```bash
# Open generated report/html on Mac host screen
mac-run open doc/report.html
```

> **Boundary**: All other tasks (git, file edits, searches, CLI tools) MUST run locally in the Linux VM. Minimize `mac-run` usage. Never access host data outside `~/.vm/.monet`.

---

## 3. Whitelist Management on Mac Host

Manage rules directly using the daemon script on the Mac host:

```bash
# List all active rules
python3 ~/.vm/mac_command_daemon.py whitelist list

# Whitelist an entire prefix (e.g. allow all ./gradlew subcommands)
python3 ~/.vm/mac_command_daemon.py whitelist add-prefix ./gradlew

# Clear all rules
python3 ~/.vm/mac_command_daemon.py whitelist clear
```

---

## 4. Security Defenses

- **Strict Directory Jailing**: Commands are confined strictly inside `~/.vm/.monet` (`~/shared`). Path traversal outside is rejected with `403 Forbidden`.
- **Anti-Injection (No Shell Execution)**: The daemon executes directly with `shell=False` (`execve`).
- **No Shell Wrappers**: Invoking shell interpreters with command execution flags (e.g. `sh -c`, `bash -c`) is blocked by default.
