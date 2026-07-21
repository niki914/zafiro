# Contributing to Nexus

This document is written for AI coding agents (Claude Code, Codex, etc.) that contributors will use to work on this repo. It tells you how to navigate the codebase, what constraints matter, and when to stop and ask the human.

## How to orient yourself

This is an Android Xposed module. It intercepts voice assistant queries on the device, sends them to an LLM, and injects the response back into the host app's UI.

**Read order**: start with `wiki/index.md` — it's the architecture map. It routes you to the right wiki page for whatever you're working on. Then read the source files that wiki page points to.

Rule: **wiki is the map, source is the truth.** If they conflict, trust the source and flag it.

## Terminology you'll see in the code

| Term | Meaning |
|------|---------|
| **Host** | The voice assistant app being hooked — Breeno (`com.heytap.speechassist`) or XiaoAi (`com.miui.voiceassist`) |
| **Main App** | Nexus's own process (`com.niki914.nexus.agentic`) — settings UI + `AgentRuntimeService` |
| **takeover** | Decision per query: `InjectedLLM` (Nexus answers) or `NativeTakeover` (let host answer natively) |
| **turn** | One query→response lifecycle, tracked by `ConversationTurnState` |
| **store** | A named JSON persistence unit (e.g. `agent.main.config`, `rules.takeover`), atomically written under `filesDir/` |
| **render pipeline** | The path that injects LLM output into host UI. Breeno replaces cards wholesale; XiaoAi captures response targets and injects in chunks |

## Module boundaries

| Module | What lives here | When you're changing it |
|--------|----------------|------------------------|
| `app/` | Xposed entry point, host routing, settings UI (Compose), `AgentRuntimeService`, Breeno/XiaoAi hooks | Almost every task touches this. Be specific about which subpackage. |
| `agent-runtime/` | `LLMController`, prompt assembly, Tool/Skill/MCP execution, streaming event mapping | Pure Kotlin, no Android UI. Changes here affect how the LLM is called and what tools it can use. |
| `xposed-api/` | Xposed event types, utility functions, shared constants | Shared types between main app and host processes. |
| `xposed-runtime/` | Xposed runtime, hook base classes, Context/Activity observation | Low-level Xposed infrastructure. Change rarely, test carefully. |
| `store/` | Store persistence, IPC bridge (`XIpcBridge`), config serialization | **Critical**: IPC changes affect both the main app process AND the Xposed host process. Both sides must stay compatible. |
| `ui-kit/` | Shared Compose primitives, `LiquidScreen` shell, navigation controller | UI infrastructure. Used by `app/` UI code. |

## Build

Requirements: **JDK 17** + Android SDK.

```bash
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

For release builds the contributor needs their own signing key. See README.

## Commit style

[Conventional Commits](https://www.conventionalcommits.org/). Prefixes used in this repo: `feat:`, `fix:`, `refactor:`, `docs:`, `chore:`.

Look at recent commits to match the existing style:
```
feat: add X capability
fix: specific bug description
refactor: what was restructured and why
```

One logical change per commit. Don't bundle unrelated cleanups with a feature.

## Critical constraints

These are things the code won't tell you:

### Xposed hooks are fragile

Hook points target specific methods in the host voice assistant app. A host app update can break them silently. When modifying hooks in `app/src/main/java/.../mod/feat/`, the human must test on a real device — the emulator cannot run Xposed. If a hook change looks risky, say so and ask the human to verify against multiple host app versions.

### IPC crosses process boundaries

The AIDL definitions in `app/src/main/java/.../runtime/ipc/` are the contract between the main app process and the Xposed host process. The `store/` module (`XIpcBridge`) handles config persistence and notification IPC. When you change an AIDL interface or the StoreClient contract, both sides must be rebuilt and tested together. Don't add methods without checking all callers on both sides.

### Never commit secrets

API keys, tokens, signing keystores — none of these belong in the repo. If you see a placeholder or `BuildConfig` field for a secret, don't fill it with a real value.

### Dependencies have real cost

This is an Android app that bundles multiple runtimes. Every new library adds build time and method count. Before adding a dependency, consider whether the same thing can be done with what's already in the project or the standard library.

## When to stop and ask the human

These scenarios need human judgment — don't proceed without asking:

- **Adding a new Xposed hook point** — the human needs to verify it works on their target host app version
- **Changing AIDL interfaces** — the human needs to test both processes together
- **Adding a dependency** — the human needs to accept the build/method-count cost
- **Changing the render pipeline** — the human must test on-device that LLM responses still appear correctly in the host UI
- **Anything that touches `AndroidManifest.xml`** — permissions, exported services, intent filters all have security implications

## Testing

No automated test suite exists yet. The primary verification method is building the APK and testing on a real device. When you finish a change, tell the human what to test and what to look for — especially if the change touches hooks, IPC, or the render pipeline.

## Documentation

Architecture docs live in `wiki/`. If your change alters module boundaries, hook points, IPC contracts, or the config/store model, update the relevant wiki pages. Follow the existing format: state the fact, link to the source file that backs it.

## License

MIT. See [LICENSE](LICENSE).
