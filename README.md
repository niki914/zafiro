<div align="right">

English | **[中文](README_CN.md)**

</div>

# Nexus

> Connect your phone's system voice assistant to your own model, and give it the ability to call tools, execute tasks, and operate your device.

[![stars](https://img.shields.io/github/stars/niki914/agentic-nexus?label=stars)](https://github.com/niki914/agentic-nexus)
[![release](https://img.shields.io/github/v/release/niki914/agentic-nexus?include_prereleases)](https://github.com/niki914/agentic-nexus/releases/latest)
[![downloads](https://img.shields.io/github/downloads/niki914/agentic-nexus/total)](https://github.com/niki914/agentic-nexus/releases/latest)

<table align="center">
<tr>
<td align="center" valign="middle"><img src="https://github.com/niki914/agentic-nexus/blob/main/res/gh_mcp.gif?raw=true" alt="Using GitHub tools via MCP" width="200"/></td>
<td align="center" valign="middle"><img src="https://github.com/niki914/agentic-nexus/blob/main/res/hyper.gif?raw=true" alt="Nexus voice assistant interaction demo" width="200"/></td>
<td align="center" valign="middle"><img src="https://github.com/niki914/agentic-nexus/blob/main/res/magisk.gif?raw=true" alt="Operating an Android device via terminal" width="200"/></td>
</tr>
</table>

Nexus is an extensible voice Agent for Android.

It can take over some of the system voice assistant entry points, hand your voice requests to a custom model, and complete real tasks through capabilities such as Skills, MCP, Shell, and SSH.

You can choose your own model service, and configure tools and behavior rules. For requests that should not be handled by the Agent, Nexus can also hand them back to the original system assistant.

> [!IMPORTANT]
> Nexus is still in Beta — functionality, compatibility, and stability are being continuously improved.
>
> The source is now open. You can download a release from [Releases](https://github.com/niki914/agentic-nexus/releases/latest), or refer to [Build from Source](#build-from-source) to compile it yourself.

> [!NOTE]
> The core "system voice assistant takeover" feature requires **Root + [LSPosed](https://github.com/lsposed/lsposed)**, and currently only supports:
>
> - OPPO / OnePlus / Realme Breeno
> - Xiaomi XiaoAi
>
> More devices and voice assistants are being continuously adapted. Actual availability may be affected by phone model, system version, voice assistant version, and vendor system restrictions. When a device does not yet support system assistant takeover, you can still use Nexus's built-in chat interface with all Agent capabilities.

## Core Capabilities

### Extensible Agent System

Nexus is not just about swapping the language model behind your voice assistant. It provides an extensible Agent runtime that lets the model call tools, access external services, and execute multi-step tasks based on your requests.

- **Skills**: Extend the Agent's task-handling ability through reusable skill descriptions
- **MCP**: Connect to external tools and data sources such as GitHub, Notion, and HomeKit
- **Takeover Rules**: Define which requests are handled by Nexus and which are handed back to the system assistant
- **Memory System**: Persist user preferences and long-term context across sessions
- **Conversation History**: View, continue, or fork past Agent conversations

<table align="center">
<tr>
<td align="center" valign="middle"><img src="https://github.com/niki914/agentic-nexus/blob/main/res/agentic/termux_long_en.jpg?raw=true" alt="Executing tasks via Termux" width="200"/></td>
<td align="center" valign="middle"><img src="https://github.com/niki914/agentic-nexus/blob/main/res/agentic/headless_cc_en.jpg?raw=true" alt="Calling Claude Code remotely" width="200"/></td>
<td align="center" valign="middle"><img src="https://github.com/niki914/agentic-nexus/blob/main/res/agentic/skill_long_en.jpg?raw=true" alt="Extending Agent capabilities with Skills" width="200"/></td>
</tr>
</table>

### Android System Capabilities

Nexus can execute terminal tasks at different privilege levels depending on the device environment and user authorization:

1. **Normal app permissions**: Perform basic operations that require no extra authorization
2. **[Shizuku](https://github.com/rikkaapps/shizuku)**: Gain more complete Android system operation capabilities
3. **Root**: Execute advanced system tasks on rooted devices

Different privilege levels correspond to different available capabilities. Nexus does not require the device to be rooted; users can choose according to their own device conditions.

### Shell & SSH

Nexus can connect to local or remote terminal environments:

- Connect to [Termux](https://github.com/termux/termux-app) to use Linux commands and tools on your Android device
- Connect to a dev machine or server via SSH to execute remote tasks
- Let the Agent generate commands, execute them, and analyze results based on natural-language requests

As a result, you don't need to write every terminal command by hand — just describe the goal, and the Agent plans and completes the corresponding operations.

### Headless AI Coding

Through SSH, Nexus can connect to [Claude Code](https://github.com/anthropics/claude-code) running on a dev machine or server.

You can issue development tasks directly by voice from your phone, and Nexus will connect to the remote environment, invoke the Coding Agent, and bring the results back to the current conversation.

## Model Support

Nexus supports configuring a variety of model services, including:

- OpenAI
- Anthropic
- Google
- DeepSeek
- Compatible endpoints

Nexus does not provide built-in model accounts. Before use, you need to prepare an API Key for the corresponding model service and configure it in the app.

## Technical Components

Nexus is built on the following projects:

- AI Conversations & Tooling: [s3ss10n](https://github.com/niki914/s3ss10n)
- User Interface: Material 3 Expressive × [AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)
- Shell & SSH: [libterm](https://github.com/niki914/libterm)

## Download & Usage

Please head to [Releases](https://github.com/niki914/agentic-nexus/releases/latest) to download the latest version.

Before first use, please prepare:

- A compatible Android device
- At least one API Key for a supported model service
- Configure Shizuku, Root, Termux, or SSH as needed (optional)

For specific configuration methods and current version support, please refer to the in-app instructions.

## Build from Source

The source of this repository is open — you can also compile it yourself.

**Requirements**: JDK 17 and the Android SDK (or Android Studio directly).

### Debug Build

The Debug build uses an auto-generated debug signature, no extra setup needed:

```bash
./gradlew assembleDebug
```

Output is at `app/build/outputs/apk/debug/app-debug.apk`.

### Release Build

Official releases are signed with the author's private key, which is not included in this repository. To build a Release version yourself, use your own signing key:

```bash
# 1. Generate a keystore
keytool -genkeypair -v -keystore my-release.jks \
  -keyalg RSA -keysize 2048 -validity 10000 -alias my_key

# 2. Pass signing info via -P (or write it into gradle.properties to replace the placeholders)
./gradlew assembleRelease \
  -PRELEASE_STORE_FILE=/abs/path/my-release.jks \
  -PRELEASE_STORE_PASSWORD=yourStorePassword \
  -PRELEASE_KEY_ALIAS=my_key \
  -PRELEASE_KEY_PASSWORD=yourKeyPassword
```

Output is at `app/build/outputs/apk/release/app-release.apk`.

> [!NOTE]
> A self-signed Release build has a different signature from the official release and cannot be installed directly over a device that already has the official version (you must uninstall it first).

## Community & Feedback

Join the [Telegram community](https://t.me/+ZPX2xtSl6RwyZGNl) to chat, ask questions, or report issues.

If you run into problems, please try to provide the following information:

- Phone model and Android system version
- The system voice assistant currently in use
- The system voice assistant version
- Nexus version
- Steps to reproduce
- Screenshots or screen recordings
- Relevant logs (if available)

Complete information can significantly improve the efficiency of diagnosing issues.

## Notes

Nexus is still in a phase of rapid iteration, and some features may change. Operations involving Shell, Shizuku, or Root carry higher system privileges — please use them with an understanding of the relevant commands and risks.
