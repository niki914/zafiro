<div align="right">

**[English](README.md)** | 中文

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

## 什么是 Zafiro?

Zafiro 是一个运行在 Android 设备上的智能 Agent，完全开源，支持 [BYOK](https://www.ibm.com/think/topics/byok)。它能看懂你的屏幕，操控你的设备，完成各种 App 操作——并完整支持 Skills、MCP 与记忆。也能原生运行 Shell 和 Python 3 并自行封装工具，完成网络搜索、文件下载等任务，或通过 SSH 对接远程开发机。

<table align="center" width="100%">
  <tr>
    <th width="25%" align="center">Phone-Use</th>
    <th width="25%" align="center">网页生成</th>
    <th width="25%" align="center">外部 Agent</th>
    <th width="25%" align="center">多模态</th>
  </tr>
  <tr>
    <td width="25%" align="center"><img src="https://github.com/niki914/zafiro/blob/main/res/demos/zafiro__phone_control.gif?raw=true" alt="基于屏幕指针的自主移动端 GUI 导航与设备控制" width="90%" /></td>
    <td width="25%" align="center"><img src="https://github.com/niki914/zafiro/blob/main/res/demos/zafiro__html_generation.gif?raw=true" alt="动态代码生成与本地浏览器即时预览" width="90%" /></td>
    <td width="25%" align="center"><img src="https://github.com/niki914/zafiro/blob/main/res/demos/zafiro__pi_agent_mcp.gif?raw=true" alt="向外部 Agent 开放系统能力的 Android MCP 服务器" width="90%" /></td>
    <td width="25%" align="center"><img src="https://github.com/niki914/zafiro/blob/main/res/demos/zafiro__image_understanding.jpg?raw=true" alt="端侧多模态视觉与图像理解" width="90%" /></td>
  </tr>
  <tr>
    <th width="25%" align="center">模型服务商</th>
    <th width="25%" align="center">Termux</th>
    <th width="25%" align="center">安全拦截</th>
    <th width="25%" align="center">会话管理</th>
  </tr>
  <tr>
    <td width="25%" align="center"><img src="https://github.com/niki914/zafiro/blob/main/res/demos/zafiro__multi_providers.jpg?raw=true" alt="自定义大模型 API 密钥与多供应商配置" width="90%" /></td>
    <td width="25%" align="center"><img src="https://github.com/niki914/zafiro/blob/main/res/demos/zafiro__remote_machine_ssh.jpg?raw=true" alt="通过 SSH 与 Termux 集成远程开发机" width="90%" /></td>
    <td width="25%" align="center"><img src="https://github.com/niki914/zafiro/blob/main/res/demos/zafiro__safety_guard.jpg?raw=true" alt="敏感 Shell 操作的人机协同权限防护与安全拦截" width="90%" /></td>
    <td width="25%" align="center"><img src="https://github.com/niki914/zafiro/blob/main/res/demos/zafiro__conversation_management.jpg?raw=true" alt="本地会话历史管理与会话分支" width="90%" /></td>
  </tr>
</table>

> [!IMPORTANT]
> 对于原生应用，Zafiro 通过无障碍即可完成手机控制，对非原生应用亦可通过 [Shizuku](https://github.com/RikkaApps/Shizuku) 实现操控而无需 Root，Root 后可获得更完整的体验。
>
> 请前往 [Releases](https://github.com/niki914/zafiro/releases/latest) 下载发布版本，或选择[从源码构建](#从源码构建)。

## 核心能力

### 现代的 UI 设计语言

- **[Material 3 Expressive](https://m3.material.io/) & Apple Liquid Glass** - 现代、精美的界面
- **个性化主题** - 多种主题色，支持深色模式与动态取色
- **多语言支持** - English、中文、日本語、Español、Русский
- **后台交互** - 支持美观的悬浮球和通知栏 UI，便于实时跟进 Agent

### 手机操控

- **屏幕交互** - 打开应用、挑选音乐、点外卖，皆可胜任
- **全程可见** - 屏幕上的指针展示 Agent 的每一步操作

### Agent 系统

- **开箱即用** - 内置 OpenAI、Anthropic、Google Gemini、DeepSeek、OpenRouter、Kimi、阿里百炼、硅基流动等
- **按需扩展** - 完整支持 Skills、MCP，并拥有记忆和工作区，随意扩展
- **权限管理** - 通过配置规则，每一条运行的命令和代码都由你管控
- **上下文管理** - 随时查看上下文占用并进行压缩
- **对话管理** - 你的每一次对话都能进行分支、回退与重新生成，数据仅保留在本地。同一个会话中，模型可以随意切换

### Python 工具

- **直接运行代码** - 在设备上原生运行 Python 3
- **Meta 工具** - 支持封装自定义 Python 工具，Agent 能复用、维护它创造的工具
- **内置场景** - 网络搜索、网页内容读取、APK 安装开箱即用

### 远程环境

- **[Termux](https://github.com/termux/termux-app)** - 在 Android 设备上使用 Linux 命令与工具
- **SSH** - 连接开发机或服务器执行远程任务
- **[Claude Code](https://github.com/anthropics/claude-code)** - 用手机下达开发任务，由远端 Coding Agent 执行

## 社区

- [Telegram](https://t.me/+ZPX2xtSl6RwyZGNl) — 交流、提问、反馈问题
- [GitHub Issues](https://github.com/niki914/zafiro/issues) — 提交 Bug 或功能请求

如果遇到问题，请尽量提供：手机型号与 Android 版本、系统语音助手及版本、Zafiro 版本、复现步骤、截图或录屏。

## 赞助

我们接受通过以下渠道的赞助：

- [爱发电](https://afdian.com/a/niki914)
- [![Tip in Crypto](https://tip.md/badge.svg)](https://tip.md/niki914) - 0x5f1469519c87edf2245ea04fbb54284c9b76ad3d

## 语音助手接管

通过 [LSPosed](https://github.com/lsposed/lsposed) 框架，Zafiro 可以接管系统语音助手——唤醒小布或小爱，实际应答你的是你自己的 Agent。支持根据关键词决定哪些请求交给 Zafiro、哪些放行给原生助手。接管后的助手保留包括设备操控在内的全部 Agent 能力。

> [!NOTE]
> 接管系统语音助手需要 **Root + LSPosed**，目前支持：OPPO 系列 - 小布助手
>
> 接管可用性可能受机型、系统版本、语音助手版本及厂商系统限制影响。当你的设备暂不支持系统助手接管时，仍可使用 Zafiro 的聊天界面使用全部 Agent 能力。

<details>
<summary>查看演示</summary>

<table align="center" width="100%">
  <tr>
    <th width="50%" align="center">小布助手</th>
    <th width="50%" align="center">小爱同学</th>
  </tr>
  <tr>
    <td width="50%" align="center"><img src="https://github.com/niki914/zafiro/blob/main/res/takeover/breeno__termux.gif?raw=true" alt="ColorOS 小布助手系统语音助手接管演示" width="80%" /></td>
    <td width="50%" align="center"><img src="https://github.com/niki914/zafiro/blob/main/res/takeover/hyper__intro.gif?raw=true" alt="HyperOS 小爱同学系统语音助手接管演示" width="80%" /></td>
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
| SVG 渲染      | [coil-resvg](https://github.com/hash-sequence/coil-resvg) (resvg)    |
| G2 圆角       | [Capsule](https://github.com/Kyant0/Capsule)                         |

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
./
├── app/                 # 主应用：Compose UI、AgentRuntimeService、Xposed 钩子
├── business/            # 业务核心层（契约与实现分离）
│   ├── api/             # 业务对外公共契约接口
│   ├── agent/           # Agent 核心逻辑、会话编排与状态流
│   ├── files/           # 文件附件与路径解析
│   ├── notification/    # 常驻通知、前台服务与通知渠道管理
│   ├── permission/      # 权限管理中心（Root / Shizuku 自动授权、统一审批）
│   └── application/     # 应用管理与生命周期
├── agent-runtime/       # Agent 运行时：LLM 调用、工具/Skill/MCP 执行、Python 运行时
├── xsettings/           # 轻量配置模块（业务按需依赖）
├── store/               # Store 持久化、IPC 桥（XIpcBridge）
├── ui-kit/              # 共享 Compose 组件、LiquidScreen 壳、导航
├── remote-view/         # 跨进程 / 悬浮窗 RemoteView 视图
├── xposed-api/          # Xposed 事件类型、共享常量（主应用与宿主进程共享）
├── xposed-runtime/      # Xposed 运行时、Hook 基类
└── libs/
    ├── logging/         # 日志库
    ├── okia/            # Okia Agent 运行时基础库
    └── libterm/         # 终端库（含多个后端：libsu, shizuku, ssh 等）
```

## Roadmap

- 系统语音助手实现（非 Xposed 接管）
- 应用外文件导入 / 分享
- Termux 等更多内置技能，`/skill` 提示和云端导入 Skill

## 贡献

欢迎提交 Pull Request!

### 步骤

1. Fork 本项目
2. 创建功能分支（`git checkout -b feat/your-feature`）
3. 提交你的改动（`git commit -m 'feat: add your feature'`，遵循 [Conventional Commits](https://www.conventionalcommits.org/zh-hans/)）
4. 推送到分支（`git push origin feat/your-feature`）
5. 打开一个 Pull Request

### 使用你的 Coding Agent

在使用 Codex 这类 Coding Agent 来贡献本项目前，确保它们阅读了项目的 [AGENTS.md](AGENTS.md)

## 许可证

MIT — 详见 [LICENSE](LICENSE)。

<p align="center">
  Made with ✨️ by <a href="https://github.com/niki914">niki914</a>
</p>
