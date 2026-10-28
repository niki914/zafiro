# Daily Usage (Configured Mode)

Use this guide when the Mac build daemon is already running on the Mac host.

## 1. Quick Health Check

Before triggering builds, verify that the host daemon is reachable from inside the Linux VM:

```bash
curl -s http://host.lima.internal:9876/health
```

Expected response:
```json
{
  "status": "ok",
  "message": "Mac Build Daemon is running",
  "base_dir": "/Users/niki/.vm/.monet"
}
```

If connection is refused, the daemon is not running on the Mac host. Refer to [environment-setup.md](environment-setup.md) to start it.

---

## 2. Running Builds via `mac-run`

Navigate to any project directory located under `~/shared` in the Linux VM, and prepend `mac-run` before the build command:

### Android / Gradle
```bash
# In ~/shared/repo/zafiro
mac-run ./gradlew assembleDebug

# Run unit tests on Mac
mac-run ./gradlew testDebugUnitTest
```

### Other Projects
```bash
# Rust / Cargo
mac-run cargo build --release

# Node / Web
mac-run npm run build
```

---

## 3. How It Works

1. **Path Resolution**: `mac-run` inspects current working directory (e.g. `~/shared/repo/zafiro`), extracts relative path (`repo/zafiro`), and sends request to Mac host.
2. **Mac Host Execution**: Mac host resolves path under `~/.vm/.monet/repo/zafiro` and executes the command natively on Apple Silicon.
3. **Log Streaming**: stdout and stderr are streamed chunk-by-chunk in real time back to the Linux VM terminal.
4. **Exit Code Propagation**: The exact process exit code is forwarded. If the build fails on Mac, `mac-run` exits with the non-zero code.
5. **Artifact Sharing**: Build outputs (such as `app/build/outputs/apk/debug/app-debug.apk`) reside in the shared filesystem and are immediately accessible inside the Linux VM.

---

## 4. Operational Best Practices

- **Avoid Dual-OS Build Conflicts**: Do not run `./gradlew` inside the Linux VM once Mac delegated builds are used. Different OS binaries (like AAPT2 Linux vs macOS ARM64) will invalidate or corrupt shared Gradle caches.
- **Tailing Long Output**: If you only want summary logs:
  ```bash
  mac-run ./gradlew assembleDebug > /tmp/build.log 2>&1
  tail -n 50 /tmp/build.log
  ```
