<div align="right">

**[中文](README_CN.md)** | English

</div>

<p align="center">
  <img src="https://github.com/niki914/zafiro/blob/main/res/zafiro_settings_screen.png?raw=true" alt="zafiro"/>
</p>

<p align="center">
  <a href="https://github.com/niki914/zafiro"><img src="https://img.shields.io/github/stars/niki914/zafiro?label=stars" alt="stars"/></a>
  <a href="https://github.com/niki914/zafiro/releases/latest"><img src="https://img.shields.io/github/v/release/niki914/zafiro?include_prereleases" alt="release"/></a>
  <a href="https://github.com/niki914/zafiro/releases/latest"><img src="https://img.shields.io/github/downloads/niki914/zafiro/total" alt="downloads"/></a>
</p>

<p align="center">
Android Native Agent · Phone-Use · Skills · MCP
</p>

## What is Zafiro?

Zafiro is an intelligent agent running on your Android phone. We provide the Zafiro Agent with a full set of scaffolding so it can understand your screen, control your device, and carry out operations across apps. It can wrap tools on its own with Python to perform tasks such as web search, file downloads, and more. Zafiro supports memory, MCP, and Skills, and can also connect to a remote dev machine via SSH.

<table align="center">
<tr>
<tr>
<td align="center"><img src="https://github.com/niki914/zafiro/blob/main/res/zafiro_phone_use.gif?raw=true" alt="Zafiro device control demo" width="135" height="300"/></td>
<td align="center"><img src="https://github.com/niki914/zafiro/blob/main/res/zafiro_net_research.gif?raw=true" alt="Zafiro web research demo" width="135" height="300"/></td>
<td align="center"><img src="https://github.com/niki914/zafiro/blob/main/res/zafiro_native.gif?raw=true" alt="Zafiro app installation demo" width="135" height="300"/></td>
<td align="center"><img src="https://github.com/niki914/zafiro/blob/main/res/zafiro_settings_screen.png?raw=true" alt="Zafiro settings screen" width="142" height="300"/></td>
</tr>
<tr>
<td align="center">Device Control</td>
<td align="center">Research</td>
<td align="center">Install Apps from the Web</td>
<td align="center">Settings</td>
</tr>
</tr>
</table>

> [!IMPORTANT]
> Zafiro is still in Beta — functionality and experience are being continuously improved.
>
> You can download a release from [Releases](https://github.com/niki914/zafiro/releases/latest), or build from source.

## Core Capabilities

### Modern UI Design Language

- **[MD3E](https://m3.material.io/) & Apple Liquid Glass** - a modern, polished interface
- **Dynamic Theming** - multiple accent colors with dark / light mode switching
- **Multilingual Support** - Chinese, English, Japanese, Spanish, and more

### Device Control

- **Screen Interaction** - open apps, fill forms, and switch pages in one go
- **Fully Visible** - an on-screen pointer animation shows every step the Agent takes

### Agent System

- **Out of the Box** - built-in Skills, MCP, memory, and takeover rules, no configuration needed
- **Extend as Needed** - supports custom tools, extend it your way
- **Permission Management** - every command that runs is under control

### Python Tools

- **Run Code Directly** - run Python natively on the device
- **Meta-Tooling** - wrap your own Python tools
- **Built-in Scenarios** - web search, web page reading, and APK installation work out of the box

### Remote Environments

- **[Termux](https://github.com/termux/termux-app)** - use Linux commands and tools on your Android device
- **SSH** - connect to a dev machine or server to execute remote tasks
- **[Claude Code](https://github.com/anthropics/claude-code)** - issue dev tasks from your phone, executed by a remote Coding Agent

## Voice Assistant Takeover

Through the [LSPosed](https://github.com/lsposed/lsposed) framework, Zafiro can take over your system voice assistant — wake Breeno or XiaoAi, and the one actually answering is your own Agent. You can decide, based on keywords, which requests go to Zafiro and which pass through to the native assistant. After takeover, the assistant retains full Agent capabilities including device control.

> [!NOTE]
> Taking over the system voice assistant requires **Root + LSPosed**, and currently supports:
>
> - OPPO / OnePlus / Realme | Breeno Assistant
> - ~~Xiaomi | XiaoAi~~ (no longer maintained — community contributors welcome)
>
> Voice takeover availability may be affected by phone model, system version, voice assistant version, and vendor system restrictions. When your device does not yet support system assistant takeover, you can still use Zafiro's chat interface with all Agent capabilities.

<details>
<summary>Demos</summary>

<table align="center">
<tr>
<td align="center" valign="middle"><img src="https://github.com/niki914/zafiro/blob/main/res/breeno_github_mcp.gif?raw=true" alt="Zafiro voice assistant takeover demo" width="200"/></td>
<td align="center" valign="middle"><img src="https://github.com/niki914/zafiro/blob/main/res/hyper_intro.gif?raw=true" alt="Zafiro voice assistant takeover demo" width="200"/></td>
<td align="center" valign="middle"><img src="https://github.com/niki914/zafiro/blob/main/res/breeno_magisk.gif?raw=true" alt="Zafiro voice assistant takeover demo" width="200"/></td>
</tr>
</table>

</details>

## Tech Stack

| Category         | Technology                                                                   |
|------------|----------------------------------------------------------------------|
| Language         | [Kotlin](https://kotlinlang.org/)                                    |
| UI Framework      | [Jetpack Compose](https://developer.android.com/jetpack/compose)     |
| Design Language       | [Material Design 3 Expressive](https://m3.material.io/)              |
| Liquid Glass       | [Android Liquid Glass](https://github.com/Kyant0/AndroidLiquidGlass) |
| Agent Runtime  | [Okia](https://github.com/niki914/okia)                              |
| Python Runtime | [Chaquopy](https://chaquo.com/chaquopy/)                             |
| System Takeover       | [LSPosed](https://github.com/lsposed/lsposed) + Xposed API           |
| Terminal / SSH   | [libterm](https://github.com/niki914/libterm)                        |

## Getting Started

### Build from Source

```bash
./gradlew assembleDebug
```

### Requirements

- Android Studio (or Android SDK + JDK 17)
- An Android 11 or above device

<details>
<summary>Release Signing</summary>

To build a Release version yourself, use your own signing key:

```bash
keytool -genkeypair -v -keystore my-release.jks \
  -keyalg RSA -keysize 2048 -validity 10000 -alias my_key

./gradlew assembleRelease \
  -PRELEASE_STORE_FILE=/absolute/path/to/my-release.jks \
  -PRELEASE_STORE_PASSWORD=yourStorePassword \
  -PRELEASE_KEY_ALIAS=my_key \
  -PRELEASE_KEY_PASSWORD=yourKeyPassword
```

</details>

## Project Structure

```
agentic-nexus/
├── app/                 # Main app: settings UI, AgentRuntimeService, Xposed hooks
├── agent-runtime/       # Agent runtime: LLM calls, tool/Skill/MCP execution, Python runtime
├── xposed-api/          # Xposed event types, shared constants (shared by main app and host process)
├── xposed-runtime/      # Xposed runtime, hook base classes
├── store/               # Store persistence, IPC bridge (XIpcBridge)
├── ui-kit/              # Shared Compose components, LiquidScreen shell, navigation
└── libs/
    ├── logging/         # Logging library
    ├── okia/            # Okia Agent runtime base library
    └── libterm/         # Terminal library (with multiple backends)
        ├── libterm-core
        ├── libterm-runtime
        ├── libterm-backend-libsu
        ├── libterm-backend-shizuku
        └── libterm-backend-ssh
```

## Contributing

Pull requests are welcome! Please read [CONTRIBUTING.md](CONTRIBUTING.md) before contributing.

1. Fork this project
2. Create a feature branch (`git checkout -b feat/your-feature`)
3. Commit your changes (`git commit -m 'feat: add your feature'`, following [Conventional Commits](https://www.conventionalcommits.org/en/))
4. Push to the branch (`git push origin feat/your-feature`)
5. Open a Pull Request

## Community

- [Telegram](https://t.me/+ZPX2xtSl6RwyZGNl) — discuss, ask questions, give feedback
- [GitHub Issues](https://github.com/niki914/zafiro/issues) — report bugs or request features

When reporting an issue, please include as much detail as possible: phone model and Android version, system voice assistant and its version, Zafiro version, steps to reproduce, and screenshots or screen recordings.

## License

MIT — see [LICENSE](LICENSE).

<p align="center">
  Made with ✨️ by <a href="https://github.com/niki914">niki914</a>
</p>
