# Zafiro Autonomous

An Android AI agent that works **on its own**: it runs scheduled tasks while the app is closed, pursues goals with
budgets and recovery, uses MCP servers and Skills, speaks many model providers, and reports back.

Fork of [niki914/zafiro](https://github.com/niki914/zafiro) (MIT). Adds an autonomy layer without rewriting the base agent.

> **Status: early development.** See [ROADMAP.md](ROADMAP.md) for what is actually built and tested. Nothing here is claimed to work until [TESTING.md](TESTING.md) says so.

## What is different from upstream
| | Upstream Zafiro | Zafiro Autonomous |
|---|---|---|
| Scheduled / unattended tasks | no | **in progress** (Phase 2) |
| Run budgets (steps/time/tokens) | no | planned (Phase 2a) |
| Telemetry | on by default → author's server | **removed / off** |
| Languages | 5 | English only |
| App id | `com.niki914.zafiro` | `com.niki914.zafiro.auto` (installs side by side) |
| CI | release only | build + unit tests + lint + APK artifact on every push |

## Install (debug build from CI)
1. Push to a `feat/*` branch or run the **CI** workflow → download the `zafiro-autonomous-debug-apk` artifact.
2. Install the APK (it coexists with upstream Zafiro). Android 8+ (minSdk 26); developed on Android 12.

## Documentation
[CLAUDE.md](CLAUDE.md) · [ARCHITECTURE.md](ARCHITECTURE.md) · [RESEARCH.md](RESEARCH.md) · [FEATURE_COMPARISON.md](FEATURE_COMPARISON.md) ·
[IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) · [ROADMAP.md](ROADMAP.md) · [SECURITY.md](SECURITY.md) · [TESTING.md](TESTING.md) ·
[UPSTREAM_SYNC.md](UPSTREAM_SYNC.md) · [CHANGELOG.md](CHANGELOG.md) · [ADRs/](ADRs)

## License
MIT. Upstream © niki914; fork additions © TricretA. Third-party notices in [NOTICE](NOTICE).
