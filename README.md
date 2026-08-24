<div align="right">

**[中文](README_CN.md)** | English

</div>

```
███╗   ██╗███████╗██╗  ██╗██╗   ██╗███████╗
████╗  ██║██╔════╝╚██╗██╔╝██║   ██║██╔════╝
██╔██╗ ██║█████╗   ╚███╔╝ ██║   ██║███████╗
██║╚██╗██║██╔══╝   ██╔██╗ ██║   ██║╚════██║
██║ ╚████║███████╗██╔╝ ██╗╚██████╔╝███████║
╚═╝  ╚═══╝╚══════╝╚═╝  ╚═╝ ╚═════╝ ╚══════╝
```

<p align="center">
  <a href="https://github.com/niki914/agentic-nexus"><img src="https://img.shields.io/github/stars/niki914/agentic-nexus?label=stars" alt="stars"/></a>
  <a href="https://github.com/niki914/agentic-nexus/releases/latest"><img src="https://img.shields.io/github/v/release/niki914/agentic-nexus?include_prereleases" alt="release"/></a>
  <a href="https://github.com/niki914/agentic-nexus/releases/latest"><img src="https://img.shields.io/github/downloads/niki914/agentic-nexus/total" alt="downloads"/></a>
</p>

<p align="center">
Android Native Agent · Phone-Use · Skills · MCP
</p>

Zafiro runs an intelligent agent on your Android phone. It sees your screen, controls your device, and carries out tasks across apps on your behalf — with memory, MCP, Skills, and the ability to collaborate with a Coding Agent on your remote dev machine via SSH.

<p align="center">
  <img src="https://github.com/niki914/agentic-nexus/blob/main/res/nexus_demo.gif?raw=true" alt="Zafiro device control demo" width="200"/>
</p>

> The demo is an edited version. Actual operation time depends on prompt precision and model response speed.

> [!IMPORTANT]
> Zafiro is still in Beta — functionality, compatibility, and stability are being continuously improved.
>
> The source is now open. You can download a release from [Releases](https://github.com/niki914/agentic-nexus/releases/latest), or build from source.

> [!NOTE]
> Taking over the system voice assistant requires **Root + [LSPosed](https://github.com/lsposed/lsposed)**, and currently supports:
>
> - OPPO / OnePlus / Realme | Breeno Assistant
> - ~~Xiaomi | XiaoAi~~ (no longer maintained — community contributors welcome)
>
> Voice takeover availability may be affected by phone model, system version, voice assistant version, and vendor system restrictions. When your device does not yet support system assistant takeover, you can still use Zafiro's chat interface with all Agent capabilities.

## Core Capabilities

### Device Control

Powered by Android Accessibility Service and root privileges, Zafiro reads your current screen and operates apps on your behalf — from opening apps and filling forms to switching pages. A pointer animation plays on screen throughout, so every step is visible.

### Agent System

Zafiro has a built-in Agent runtime — with support for Skills, MCP, memory, and takeover rules. Extend it as needed, or use it out of the box with zero configuration.

### Connecting to Remote Environments

Zafiro can connect to local or remote terminal environments, letting the Agent execute tasks on the command line.

- Connect to [Termux](https://github.com/termux/termux-app) to use Linux commands and tools on your Android device
- Connect to a dev machine or server via SSH to execute remote tasks
- Connect to [Claude Code](https://github.com/anthropics/claude-code) — issue dev tasks by voice from your phone, and the remote Coding Agent executes them and returns results

You don't need to write every terminal command by hand — describe the goal, and the Agent plans and completes the corresponding operations.

<details>
<summary>Demos</summary>

<table align="center">
<tr>
<td align="center" valign="middle"><img src="https://github.com/niki914/agentic-nexus/blob/main/res/agentic/termux_long_en.jpg?raw=true" alt="Executing tasks via Termux" width="200"/></td>
<td align="center" valign="middle"><img src="https://github.com/niki914/agentic-nexus/blob/main/res/agentic/headless_cc_en.jpg?raw=true" alt="Calling Claude Code remotely" width="200"/></td>
</tr>
</table>

</details>

### Voice Assistant Takeover

Through the LSPosed framework, Zafiro can take over your system voice assistant — wake Breeno or XiaoAi, and your own Agent responds instead. You can decide which requests go to Zafiro vs. the native assistant based on keywords. After takeover, the assistant retains full Agent capabilities including device control.

<details>
<summary>Demos</summary>

<table align="center">
<tr>
<td align="center" valign="middle"><img src="https://github.com/niki914/agentic-nexus/blob/main/res/breeno_github_mcp.gif?raw=true" alt="Zafiro voice assistant takeover demo" width="200"/></td>
<td align="center" valign="middle"><img src="https://github.com/niki914/agentic-nexus/blob/main/res/hyper_intro.gif?raw=true" alt="Zafiro voice assistant takeover demo" width="200"/></td>
<td align="center" valign="middle"><img src="https://github.com/niki914/agentic-nexus/blob/main/res/breeno_magisk.gif?raw=true" alt="Zafiro voice assistant takeover demo" width="200"/></td>
</tr>
</table>

</details>

## Installation

Go to [Releases](https://github.com/niki914/agentic-nexus/releases/latest) to download the latest version. Before use, you'll need: a compatible Android device, at least one API key for a supported model service, and optionally Shizuku / Root / Termux / SSH as needed. Refer to the in-app instructions for specific configuration details.

To build from source:

```bash
./gradlew assembleDebug
```

To compile the okia skeleton together with its usage demo (`OkiaUsageDemo.kt` lives in `src/test`, so it needs the unit-test compile task, not `compileDebugKotlin`):

```bash
./gradlew :libs:okia:compileDebugUnitTestKotlin
```

<details>
<summary>Release signing</summary>

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

## Community

- [Telegram](https://t.me/+ZPX2xtSl6RwyZGNl) — discuss, ask questions, give feedback
- [GitHub Issues](https://github.com/niki914/agentic-nexus/issues) — report bugs or request features

When reporting an issue, please include as much detail as possible: phone model and Android version, system voice assistant and its version, Zafiro version, steps to reproduce, and screenshots or screen recordings.

## License

MIT — see [LICENSE](LICENSE).

Built by [niki914](https://github.com/niki914)
