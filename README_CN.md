<div align="right">

**[English](README.md)** | 中文

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

## 什么是 Zafiro?

Zafiro 是你的 Android 手机上运行一个智能代理。我们为 Zafiro Agent 提供了充分的脚手架，使得它能看懂你的屏幕，操控你的设备，完成各种 App 操作。它能自行用 Python 封装各种工具来完成：网络搜索、下载文件或其他功能。Zafiro 支持记忆、MCP、Skills，也能通过 SSH 对接远程开发机

<table align="center">
<tr>
<tr>
<td align="center"><img src="https://github.com/niki914/zafiro/blob/main/res/zafiro_phone_use.gif?raw=true" alt="Zafiro 手机操控演示" width="135" height="300"/></td>
<td align="center"><img src="https://github.com/niki914/zafiro/blob/main/res/zafiro_net_research.gif?raw=true" alt="Zafiro 网络研究演示" width="135" height="300"/></td>
<td align="center"><img src="https://github.com/niki914/zafiro/blob/main/res/zafiro_native.gif?raw=true" alt="Zafiro 应用安装演示" width="135" height="300"/></td>
<td align="center"><img src="https://github.com/niki914/zafiro/blob/main/res/zafiro_settings_screen.png?raw=true" alt="Zafiro 设置界面" width="142" height="300"/></td>
</tr>
<tr>
<td align="center">手机操控</td>
<td align="center">Research</td>
<td align="center">从网络安装应用</td>
<td align="center">设置页</td>
</tr>
</tr>
</table>

> [!IMPORTANT]
> Zafiro 当前仍处于 Beta 阶段，功能和体验仍在持续改进。
>
> 可以前往 [Releases](https://github.com/niki914/zafiro/releases/latest) 下载发布版本，或从源码构建。

## 核心能力

### 现代的 UI 设计语言

- **[MD3E](https://m3.material.io/) & Apple Liquid Glass** - 现代、精美的界面
- **动态主题** - 多种主体色与深色 / 浅色模式切换
- **多语言支持** - 中文、英文、日文、西班牙语等

### 手机操控

- **屏幕交互** - 打开应用、填写表单、切换页面，一步到位
- **全程可见** - 屏幕上的指针动画展示 Agent 的每一步操作

### Agent 系统

- **开箱即用** - 内置 Skills、MCP、记忆与接管规则，无需配置
- **按需扩展** - 支持自定义工具，按你的方式扩展
- **权限管理** - 每一条运行的命令都受控

### Python 工具

- **直接运行代码** - 在设备上原生运行 Python
- **元工具** - 支持封装自定义 Python 工具
- **内置场景** - 网络搜索、网页内容读取、APK 安装开箱即用

### 远程环境

- **[Termux](https://github.com/termux/termux-app)** - 在 Android 设备上使用 Linux 命令与工具
- **SSH** - 连接开发机或服务器执行远程任务
- **[Claude Code](https://github.com/anthropics/claude-code)** - 用手机下达开发任务，由远端 Coding Agent 执行

## 语音助手接管

通过 [LSPosed](https://github.com/lsposed/lsposed) 框架，Zafiro 可以接管系统语音助手——唤醒小布或小爱，实际应答你的是你自己的 Agent。支持根据关键词决定哪些请求交给 Zafiro、哪些放行给原生助手。接管后的助手保留包括设备操控在内的全部 Agent 能力。

> [!NOTE]
> 接管系统语音助手需要 **Root + LSPosed**，目前支持：
>
> - OPPO / OnePlus / Realme | 小布助手
> - ~~小米 | 小爱同学~~（已停止维护，欢迎社区贡献者接手）
>
> 接管可用性可能受机型、系统版本、语音助手版本及厂商系统限制影响。当你的设备暂不支持系统助手接管时，仍可使用 Zafiro 的聊天界面使用全部 Agent 能力。

<details>
<summary>查看演示</summary>

<table align="center">
<tr>
<td align="center" valign="middle"><img src="https://github.com/niki914/zafiro/blob/main/res/breeno_github_mcp.gif?raw=true" alt="Zafiro 语音助手接管演示" width="200"/></td>
<td align="center" valign="middle"><img src="https://github.com/niki914/zafiro/blob/main/res/hyper_intro.gif?raw=true" alt="Zafiro 语音助手接管演示" width="200"/></td>
<td align="center" valign="middle"><img src="https://github.com/niki914/zafiro/blob/main/res/breeno_magisk.gif?raw=true" alt="Zafiro 语音助手接管演示" width="200"/></td>
</tr>
</table>

</details>

## 技术栈

| 类别         | 技术                                                                   |
|------------|----------------------------------------------------------------------|
| 语言         | [Kotlin](https://kotlinlang.org/)                                    |
| UI 框架      | [Jetpack Compose](https://developer.android.com/jetpack/compose)     |
| 设计语言       | [Material Design 3 Expressive](https://m3.material.io/)              |
| 液态玻璃       | [Android Liquid Glass](https://github.com/Kyant0/AndroidLiquidGlass) |
| Agent 运行时  | [Okia](https://github.com/niki914/okia)                              |
| Python 运行时 | [Chaquopy](https://chaquo.com/chaquopy/)                             |
| 系统接管       | [LSPosed](https://github.com/lsposed/lsposed) + Xposed API           |
| 终端 / SSH   | [libterm](https://github.com/niki914/libterm)                        |

## 快速开始

### 从源码构建

```bash
./gradlew assembleDebug
```

### 运行要求

- Android Studio（或 Android SDK + JDK 17）
- Android 11 及以上设备

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

## 项目结构

```
agentic-nexus/
├── app/                 # 主应用：设置 UI、AgentRuntimeService、Xposed 钩子
├── agent-runtime/       # Agent 运行时：LLM 调用、工具/Skill/MCP 执行、Python 运行时
├── xposed-api/          # Xposed 事件类型、共享常量（主应用与宿主进程共享）
├── xposed-runtime/      # Xposed 运行时、Hook 基类
├── store/               # Store 持久化、IPC 桥（XIpcBridge）
├── ui-kit/              # 共享 Compose 组件、LiquidScreen 壳、导航
└── libs/
    ├── logging/         # 日志库
    ├── okia/            # Okia Agent 运行时基础库
    └── libterm/         # 终端库（含多个后端）
        ├── libterm-core
        ├── libterm-runtime
        ├── libterm-backend-libsu
        ├── libterm-backend-shizuku
        └── libterm-backend-ssh
```

## 贡献

欢迎提交 Pull Request！贡献前请先阅读 [CONTRIBUTING.md](CONTRIBUTING.md)。

1. Fork 本项目
2. 创建功能分支（`git checkout -b feat/your-feature`）
3. 提交你的改动（`git commit -m 'feat: add your feature'`，遵循 [Conventional Commits](https://www.conventionalcommits.org/zh-hans/)）
4. 推送到分支（`git push origin feat/your-feature`）
5. 打开一个 Pull Request

## 社区

- [Telegram](https://t.me/+ZPX2xtSl6RwyZGNl) — 交流、提问、反馈问题
- [GitHub Issues](https://github.com/niki914/zafiro/issues) — 提交 Bug 或功能请求

如果遇到问题，请尽量提供：手机型号与 Android 版本、系统语音助手及版本、Zafiro 版本、复现步骤、截图或录屏。

## 许可证

MIT — 详见 [LICENSE](LICENSE)。

<p align="center">
  Made with ✨️ by <a href="https://github.com/niki914">niki914</a>
</p>
