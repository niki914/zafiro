<div align="right">

**[English](README.md)** | 中文

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

Nexus 是你的 Android 手机上运行一个智能代理。它能看懂你的屏幕，操控你的设备，代你完成各种 App 操作，它有记忆、MCP、Skills，也能通过 SSH 对接你远程开发机上的 Coding Agent 进行协作

<p align="center">
  <img src="https://github.com/niki914/agentic-nexus/blob/main/res/nexus_demo.gif?raw=true" alt="Nexus 手机操控演示" width="200"/>
</p>

> 演示为剪辑版本。实际操作耗时取决于指令精准度与模型的响应速度

> [!IMPORTANT]
> Nexus 当前仍处于 Beta 阶段，功能、兼容性与稳定性仍在持续改进。
>
> 项目源码已开放，你可以前往 [Releases](https://github.com/niki914/agentic-nexus/releases/latest) 下载发布版本，或从源码构建。

> [!NOTE]
> 接管系统语音助手入口需要 **Root + [LSPosed](https://github.com/lsposed/lsposed)**，目前适配：
>
> - 欧加真 | 小布助手
> - ~~小米 | 小爱同学~~（暂不维护，欢迎社区贡献者协助适配）
>
> 语音接管实际可用性可能受手机型号、系统版本、语音助手版本及厂商系统限制的影响。当设备暂不支持系统助手接管时，仍可使用 Nexus 的对话界面与全部 Agent 能力。

## 核心能力

### 手机操控

基于 Android 无障碍服务与 Root 权限，Nexus 能读懂当前屏幕内容，并代替你完成各种 App 操作——从打开应用、填写表单到切换页面，全程屏幕上会显示指针动画，每一步都清晰可见。

### Agent 系统

Nexus 内置 Agent 运行时——支持 Skills、MCP、记忆与接管规则。你可以按需扩展，也可以什么都不配，开箱即用。

### 连接远程环境

Nexus 可以连接本地或远程终端环境，让 Agent 在命令行中执行任务。

- 连接 [Termux](https://github.com/termux/termux-app)，在 Android 设备上使用 Linux 命令和工具
- 通过 SSH 连接开发机或服务器，执行远程任务
- 连接 [Claude Code](https://github.com/anthropics/claude-code)，用手机下达开发任务，由远端 Coding Agent 执行并返回结果

你不需要手动编写每一条终端命令——描述目标，由 Agent 规划并完成操作。

<details>
<summary>查看演示</summary>

<table align="center">
<tr>
<td align="center" valign="middle"><img src="https://github.com/niki914/agentic-nexus/blob/main/res/agentic/termux_long_cn.jpg?raw=true" alt="通过 Termux 执行任务" width="200"/></td>
<td align="center" valign="middle"><img src="https://github.com/niki914/agentic-nexus/blob/main/res/agentic/headless_cc_cn.jpg?raw=true" alt="远程调用 Claude Code" width="200"/></td>
</tr>
</table>

</details>

### 语音助手接管

通过 LSPosed 框架，Nexus 可以接管系统语音助手——唤醒小布或小爱，实际应答你的是你自己的 Agent。支持根据关键词决定使用 Nexus 或原生助手，接管后的助手同样可以使用 phone-use 等全部 Agent 能力。

<details>
<summary>查看演示</summary>

<table align="center">
<tr>
<td align="center" valign="middle"><img src="https://github.com/niki914/agentic-nexus/blob/main/res/breeno_github_mcp.gif?raw=true" alt="Nexus 语音助手接管演示" width="200"/></td>
<td align="center" valign="middle"><img src="https://github.com/niki914/agentic-nexus/blob/main/res/hyper_intro.gif?raw=true" alt="Nexus 语音助手接管演示" width="200"/></td>
<td align="center" valign="middle"><img src="https://github.com/niki914/agentic-nexus/blob/main/res/breeno_magisk.gif?raw=true" alt="Nexus 语音助手接管演示" width="200"/></td>
</tr>
</table>

</details>

## 安装

前往 [Releases](https://github.com/niki914/agentic-nexus/releases/latest) 下载最新版本。使用前需准备：一台兼容的 Android 设备、至少一个模型服务的 API Key，以及按需配置的 Shizuku / Root / Termux / SSH。具体配置以应用内说明为准。

如需从源码构建：

```bash
./gradlew assembleDebug
```

<details>
<summary>Release 签名</summary>

若要自行构建 Release 版本，需使用你自己的签名密钥：

```bash
keytool -genkeypair -v -keystore my-release.jks \
  -keyalg RSA -keysize 2048 -validity 10000 -alias my_key

./gradlew assembleRelease \
  -PRELEASE_STORE_FILE=/绝对路径/my-release.jks \
  -PRELEASE_STORE_PASSWORD=你的库密码 \
  -PRELEASE_KEY_ALIAS=my_key \
  -PRELEASE_KEY_PASSWORD=你的密钥密码
```

</details>

## 社区

- [Telegram](https://t.me/+ZPX2xtSl6RwyZGNl) — 交流、提问、反馈问题
- [GitHub Issues](https://github.com/niki914/agentic-nexus/issues) — 提交 Bug 或功能请求

如果遇到问题，请尽量提供：手机型号与 Android 版本、系统语音助手及版本、Nexus 版本、复现步骤、截图或录屏。

## 许可证

MIT — 详见 [LICENSE](LICENSE)。

由 [niki914](https://github.com/niki914) 构建
