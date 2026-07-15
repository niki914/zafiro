<div align="right">

**[English](README.md)** | 中文

</div>

# Nexus

> 将 Android 系统语音助手连接到你自己的模型，并赋予它调用工具、执行任务和操作设备的能力。

[![stars](https://img.shields.io/github/stars/niki914/agentic-nexus?label=stars)](https://github.com/niki914/agentic-nexus)
[![release](https://img.shields.io/github/v/release/niki914/agentic-nexus?include_prereleases)](https://github.com/niki914/agentic-nexus/releases/latest)
[![downloads](https://img.shields.io/github/downloads/niki914/agentic-nexus/total)](https://github.com/niki914/agentic-nexus/releases/latest)

<table align="center">
<tr>
<td align="center" valign="middle"><img src="https://github.com/niki914/agentic-nexus/blob/main/res/gh_mcp.gif?raw=true" alt="通过 MCP 使用 GitHub 工具" width="200"/></td>
<td align="center" valign="middle"><img src="https://github.com/niki914/agentic-nexus/blob/main/res/hyper.gif?raw=true" alt="Nexus 语音助手交互演示" width="200"/></td>
<td align="center" valign="middle"><img src="https://github.com/niki914/agentic-nexus/blob/main/res/magisk.gif?raw=true" alt="通过终端操作 Android 设备" width="200"/></td>
</tr>
</table>

Nexus 是一个面向 Android 的可扩展语音 Agent。

它可以接管部分系统语音助手入口，将你的语音请求交给自定义模型处理，并通过 Skills、MCP、Shell 和 SSH 等能力完成实际任务。

你可以自行选择模型服务、配置工具与行为规则。对于不应由 Agent 处理的请求，Nexus 也可以将其交还给原有的系统助手。

> [!IMPORTANT]
> Nexus 当前仍处于 Beta 阶段，功能、兼容性与稳定性仍在持续改进。
>
> 项目源码已开放，你可以前往 [Releases](https://github.com/niki914/agentic-nexus/releases/latest) 下载发布版本，或参考 [自行构建](#自行构建) 从源码编译。

> [!NOTE]
> 核心的「接管系统语音助手」功能依赖 **Root + [LSPosed](https://github.com/lsposed/lsposed)**，目前仅适配：
>
> - OPPO / OnePlus / Realme 小布助手
> - Xiaomi 小爱同学
>
> 更多设备与语音助手仍在持续适配中，实际可用性可能受手机型号、系统版本、语音助手版本及厂商系统限制的影响。当设备暂不支持系统助手接管时，仍可使用 Nexus 内置的对话界面与全部 Agent 能力。

## 核心能力

### 可扩展的 Agent 系统

Nexus 不仅用于更换语音助手背后的语言模型。它提供了一套可扩展的 Agent 运行环境，使模型能够根据请求调用工具、访问外部服务并执行多步骤任务。

- **Skills**：通过可复用的技能描述扩展 Agent 的任务处理能力
- **MCP**：接入 GitHub、Notion、HomeKit 等外部工具与数据源
- **接管规则**：定义哪些请求由 Nexus 处理，哪些请求交还系统助手
- **记忆系统**：跨会话保存用户偏好与长期上下文
- **会话历史**：查看、继续或复刻过去的 Agent 会话

<table align="center">
<tr>
<td align="center" valign="middle"><img src="https://github.com/niki914/agentic-nexus/blob/main/res/agentic/termux_long_cn.jpg?raw=true" alt="通过 Termux 执行任务" width="200"/></td>
<td align="center" valign="middle"><img src="https://github.com/niki914/agentic-nexus/blob/main/res/agentic/headless_cc_cn.jpg?raw=true" alt="远程调用 Claude Code" width="200"/></td>
<td align="center" valign="middle"><img src="https://github.com/niki914/agentic-nexus/blob/main/res/agentic/skill_long_cn.jpg?raw=true" alt="使用 Skills 扩展 Agent 能力" width="200"/></td>
</tr>
</table>

### Android 系统能力

Nexus 可以根据设备环境和用户授权，以不同权限等级执行终端任务：

1. **普通应用权限**：执行无需额外授权的基础操作
2. **[Shizuku](https://github.com/rikkaapps/shizuku)**：获得更完整的 Android 系统操作能力
3. **Root**：在已 Root 设备上执行高级系统任务

不同权限等级对应不同的可用能力。Nexus 不要求设备必须 Root，用户可以根据自己的设备条件进行选择。

### Shell 与 SSH

Nexus 可以连接本地或远程终端环境：

- 连接 [Termux](https://github.com/termux/termux-app)，在 Android 设备上使用 Linux 命令和工具
- 通过 SSH 连接开发机或服务器，执行远程任务
- 让 Agent 根据自然语言请求生成命令、执行命令并分析结果

因此，用户不需要手动编写每一条终端命令，只需描述目标，由 Agent 规划并完成相应操作。

### 无头 AI Coding

通过 SSH，Nexus 可以连接运行在开发机或服务器上的
[Claude Code](https://github.com/anthropics/claude-code)。

你可以直接通过手机语音下达开发任务，由 Nexus 负责连接远程环境、调用 Coding Agent，并将执行结果带回当前会话。

## 模型支持

Nexus 支持配置多种模型服务，包括：

- OpenAI
- Anthropic
- Google
- DeepSeek
- 兼容接口

Nexus 不提供内置模型账号。使用前需要准备相应模型服务的 API Key，并在应用中完成配置。

## 技术组件

Nexus 基于以下项目构建：

- AI 对话与 Tooling：[s3ss10n](https://github.com/niki914/s3ss10n)
- 用户界面：Material 3 Expressive × [AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)
- Shell 与 SSH：[libterm](https://github.com/niki914/libterm)

## 下载与使用

请前往 [Releases](https://github.com/niki914/agentic-nexus/releases/latest) 下载最新版本。

首次使用前，请准备：

- 一台兼容的 Android 设备
- 至少一个受支持模型服务的 API Key
- 根据所需能力配置 Shizuku、Root、Termux 或 SSH（可选）

具体配置方式与当前版本支持情况，请以应用内说明为准。

## 自行构建

本仓库源码已开放，你也可以从源码自行编译。

**环境要求**：JDK 17、Android SDK（或直接使用 Android Studio）。

### Debug 版本

Debug 版本使用自动生成的调试签名，无需额外配置：

```bash
./gradlew assembleDebug
```

产物位于 `app/build/outputs/apk/debug/app-debug.apk`。

### Release 版本

官方发布版本使用作者的私有密钥签名，该密钥不包含在本仓库中。若要自行构建 Release 版本，需要使用你自己的签名密钥：

```bash
# 1. 生成一个密钥库（keystore）
keytool -genkeypair -v -keystore my-release.jks \
  -keyalg RSA -keysize 2048 -validity 10000 -alias my_key

# 2. 通过 -P 传入签名信息（也可写入 gradle.properties 替换占位符）
./gradlew assembleRelease \
  -PRELEASE_STORE_FILE=/绝对路径/my-release.jks \
  -PRELEASE_STORE_PASSWORD=你的库密码 \
  -PRELEASE_KEY_ALIAS=my_key \
  -PRELEASE_KEY_PASSWORD=你的密钥密码
```

产物位于 `app/build/outputs/apk/release/app-release.apk`。

> [!NOTE]
> 自行签名的 Release 版本与官方发布版本签名不同，无法直接覆盖安装到已安装官方版本的设备上（需先卸载）。

## 社区与反馈

欢迎加入 [Telegram 社区](https://t.me/+ZPX2xtSl6RwyZGNl) 交流、提问或反馈问题。

如果遇到问题，请尽量提供以下信息：

- 手机型号与 Android 系统版本
- 当前使用的系统语音助手
- 系统语音助手版本
- Nexus 版本
- 问题复现步骤
- 截图或录屏
- 相关日志（如可获取）

完整的信息可以显著提高问题定位效率。

## 说明

Nexus 仍处于快速迭代阶段，部分功能可能发生调整。涉及 Shell、Shizuku 或 Root 的操作具有更高的系统权限，请在理解相关命令与风险的前提下使用。