<div align="right">

**[中文](README_CN.md)** | English

</div>

<p align="center">
  <img src="https://github.com/niki914/zafiro/blob/main/res/banner/socialify.svg?raw=true" alt="zafiro"/>
</p>

<p align="center">
  <a href="https://github.com/niki914/zafiro"><img src="https://img.shields.io/github/stars/niki914/zafiro?label=stars" alt="stars"/></a>
  <a href="https://deepwiki.com/niki914/zafiro"><img src="https://github.com/niki914/zafiro/blob/main/res/badges/ask_deepwiki.svg?raw=true" alt="Ask DeepWiki"/></a>
  <a href="https://github.com/niki914/zafiro/releases/latest"><img src="https://img.shields.io/github/v/release/niki914/zafiro?include_prereleases" alt="release"/></a>
  <a href="https://github.com/niki914/zafiro/releases/latest"><img src="https://img.shields.io/github/downloads/niki914/zafiro/total" alt="downloads"/></a>
  <img src="https://img.shields.io/badge/kotlin-88.7k-blue" alt="kotlin lines"/>
  <a href="https://app.codacy.com/gh/niki914/zafiro/dashboard?utm_source=gh&utm_medium=referral&utm_content=&utm_campaign=Badge_grade"><img src="https://app.codacy.com/project/badge/Grade/b4cadbe5d2d74e3885106562cbd9715b" alt="Codacy"/></a>
</p>

## What is Zafiro?

Zafiro is an open-source intelligent Agent running on Android devices, with [BYOK](https://www.ibm.com/think/topics/byok) support. It understands your screen, controls your device, and carries out operations across apps — with full support for Skills, MCP, and memory. It can also run Shell and Python 3 natively and wrap tools on its own to perform tasks such as web search and file downloads, or connect to a remote dev machine via SSH.

<table align="center" width="100%">
  <tr>
    <th width="25%" align="center">Phone-Use</th>
    <th width="25%" align="center">HTML Generation</th>
    <th width="25%" align="center">External Agents</th>
    <th width="25%" align="center">Multimodal</th>
  </tr>
  <tr>
    <td width="25%" align="center"><img src="https://github.com/niki914/zafiro/blob/main/res/demos/zafiro__phone_control.gif?raw=true" alt="Autonomous mobile GUI navigation and device control via screen pointer" width="90%" /></td>
    <td width="25%" align="center"><img src="https://github.com/niki914/zafiro/blob/main/res/demos/zafiro__html_generation.gif?raw=true" alt="Dynamic code generation and instant local browser preview" width="90%" /></td>
    <td width="25%" align="center"><img src="https://github.com/niki914/zafiro/blob/main/res/demos/zafiro__pi_agent_mcp.gif?raw=true" alt="Android MCP server exposing system capabilities to external agents" width="90%" /></td>
    <td width="25%" align="center"><img src="https://github.com/niki914/zafiro/blob/main/res/demos/zafiro__image_understanding.jpg?raw=true" alt="On-device multimodal vision and image understanding" width="90%" /></td>
  </tr>
  <tr>
    <th width="25%" align="center">Model Providers</th>
    <th width="25%" align="center">Termux</th>
    <th width="25%" align="center">Safety Guard</th>
    <th width="25%" align="center">Session Management</th>
  </tr>
  <tr>
    <td width="25%" align="center"><img src="https://github.com/niki914/zafiro/blob/main/res/demos/zafiro__multi_providers.jpg?raw=true" alt="Custom LLM API keys and multi-provider configuration" width="90%" /></td>
    <td width="25%" align="center"><img src="https://github.com/niki914/zafiro/blob/main/res/demos/zafiro__remote_machine_ssh.jpg?raw=true" alt="Integrate remote dev machines via SSH and Termux" width="90%" /></td>
    <td width="25%" align="center"><img src="https://github.com/niki914/zafiro/blob/main/res/demos/zafiro__safety_guard.jpg?raw=true" alt="Human-in-the-loop permission guard and safety interception for sensitive Shell actions" width="90%" /></td>
    <td width="25%" align="center"><img src="https://github.com/niki914/zafiro/blob/main/res/demos/zafiro__conversation_management.jpg?raw=true" alt="Local conversation history management and session branching" width="90%" /></td>
  </tr>
</table>

> [!IMPORTANT]
> For native apps, Zafiro controls the phone via Accessibility Service; for non-native apps, it can also operate via [Shizuku](https://github.com/RikkaApps/Shizuku) without Root. Root users get the full experience.
>
> You can download a release from [Releases](https://github.com/niki914/zafiro/releases/latest), or choose to [build from source](#from-source).

## Core Capabilities

### Modern UI Design Language

- **[Material 3 Expressive](https://m3.material.io/) & Apple Liquid Glass** - a modern, polished interface
- **Personalized Theming** - multiple theme colors with dark mode and dynamic color extraction
- **Multilingual Support** - English, 中文, 日本語, Español, Русский
- **Background Interaction** - sleek floating ball and notification tray UI to follow and interact with the Agent in real time

### Device Control

- **Screen Interaction** - open apps, pick music, order food — all done for you
- **Fully Visible** - an on-screen pointer shows every step the Agent takes

### Agent System

- **Out-of-the-Box** - built-in support for OpenAI, Anthropic, Google Gemini, DeepSeek, OpenRouter, Kimi, Alibaba Bailian, SiliconFlow, and more
- **Extend as Needed** - full support for Skills and MCP, plus memory and a workspace — extend it your way
- **Permission Management** - with configurable rules, every command and piece of code is under your control
- **Context Management** - inspect context window usage at any time and compress it
- **Conversation Management** - branch, rewind, and regenerate any turn; all conversation data stays strictly local, and models can be switched on the fly within the same session

### Python Tools

- **Run Code Directly** - run Python 3 natively on the device
- **Meta-Tooling** - wrap your own Python tools; the Agent reuses and maintains the tools it creates
- **Built-in Scenarios** - web search, web page reading, and APK installation work out of the box

### Remote Environments

- **[Termux](https://github.com/termux/termux-app)** - use Linux commands and tools on your Android device
- **SSH** - connect to a dev machine or server to execute remote tasks
- **[Claude Code](https://github.com/anthropics/claude-code)** - issue dev tasks from your phone, executed by a remote Coding Agent

## Voice Assistant Takeover

Through the [LSPosed](https://github.com/lsposed/lsposed) framework, Zafiro can take over your system voice assistant — wake Breeno or XiaoAi, and the one actually answering is your own Agent. You can decide, based on keywords, which requests go to Zafiro and which pass through to the native assistant. After takeover, the assistant retains full Agent capabilities including device control.

> [!NOTE]
> Taking over the system voice assistant requires **Root + LSPosed**, and currently supports: OPPO - Breeno
>
> Voice takeover availability may be affected by phone model, system version, voice assistant version, and vendor system restrictions. When your device does not yet support system assistant takeover, you can still use Zafiro's chat interface with all Agent capabilities.

<details>
<summary>Demos</summary>

<table align="center" width="100%">
  <tr>
    <th width="50%" align="center">Breeno</th>
    <th width="50%" align="center">XiaoAi</th>
  </tr>
  <tr>
    <td width="50%" align="center"><img src="https://github.com/niki914/zafiro/blob/main/res/takeover/breeno__termux.gif?raw=true" alt="ColorOS Breeno voice assistant takeover demo" width="80%" /></td>
    <td width="50%" align="center"><img src="https://github.com/niki914/zafiro/blob/main/res/takeover/hyper__intro.gif?raw=true" alt="HyperOS XiaoAi voice assistant takeover demo" width="80%" /></td>
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
| SVG Rendering      | [coil-resvg](https://github.com/hash-sequence/coil-resvg) (resvg)    |
| G2 Rounded Corners | [Capsule](https://github.com/Kyant0/Capsule)                         |

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
./
├── app/                 # Main app: Compose UI, AgentRuntimeService, Xposed hooks
├── business/            # Core business layer (separation of contracts and implementations)
│   ├── api/             # Public business contract interfaces
│   ├── agent/           # Agent core logic, conversation orchestration & state flow
│   ├── files/           # File attachments, document path resolution
│   ├── notification/    # Resident notification, foreground service & channel management
│   ├── permission/      # Unified permission manager (Root / Shizuku auto-granting, tool approval)
│   └── application/     # Application management & lifecycle
├── agent-runtime/       # Agent runtime: LLM invocation, Tool/Skill/MCP execution, Python runtime
├── xsettings/           # Lightweight configuration module (depended on by modules as needed)
├── store/               # Store persistence, IPC bridge (XIpcBridge)
├── ui-kit/              # Shared Compose components, LiquidScreen shell, navigation
├── remote-view/         # Cross-process / floating RemoteView components
├── xposed-api/          # Xposed event types, shared constants (shared by main app and host process)
├── xposed-runtime/      # Xposed runtime, hook base classes
└── libs/
    ├── logging/         # Logging library
    ├── okia/            # Okia Agent runtime base library
    └── libterm/         # Terminal library (multiple backends: libsu, shizuku, ssh, etc.)
```

## Roadmap

- System voice assistant integration (non-Xposed takeover)
- File import / sharing from outside the app
- Built-in skills like Termux, `/skill` hints, and cloud skill imports

## Contributing

Pull requests are welcome!

### Steps

1. Fork this project
2. Create a feature branch (`git checkout -b feat/your-feature`)
3. Commit your changes (`git commit -m 'feat: add your feature'`, following [Conventional Commits](https://www.conventionalcommits.org/en/))
4. Push to the branch (`git push origin feat/your-feature`)
5. Open a Pull Request

### Using Your Coding Agent

Before contributing using coding agents like Codex, make sure they have read the project's [AGENTS.md](AGENTS.md).

## Community

- [Telegram](https://t.me/+ZPX2xtSl6RwyZGNl) — discuss, ask questions, give feedback
- [GitHub Issues](https://github.com/niki914/zafiro/issues) — report bugs or request features

When reporting an issue, please include as much detail as possible: phone model and Android version, system voice assistant and its version, Zafiro version, steps to reproduce, and screenshots or screen recordings.

## Sponsorship

We welcome sponsorships through the following channels:

- [Afdian](https://afdian.com/a/niki914)
- [![Tip in Crypto](https://tip.md/badge.svg)](https://tip.md/niki914) - 0x5f1469519c87edf2245ea04fbb54284c9b76ad3d

## License

MIT — see [LICENSE](LICENSE).

<p align="center">
  Made with ✨️ by <a href="https://github.com/niki914">niki914</a>
</p>
