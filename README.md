<div align="right">

**[English](README_EN.md)** | 中文

</div>

# Nexus

> 让手机里的语音助手, 接入你自己的模型, 并真正开始替你做事

[![stars](https://img.shields.io/github/stars/niki914/agentic-nexus?label=stars)](https://github.com/niki914/agentic-nexus)
[![release](https://img.shields.io/github/v/release/niki914/agentic-nexus?include_prereleases)](https://github.com/niki914/agentic-nexus/releases/latest)
[![downloads](https://img.shields.io/github/downloads/niki914/agentic-nexus/total)](https://github.com/niki914/agentic-nexus/releases/latest)

<table align="center">
<tr>
<td align="center" valign="middle"><img src="https://github.com/niki914/agentic-nexus/blob/main/res/gh_mcp.gif?raw=true" alt="gh_mcp" width="200"/></td>
<td align="center" valign="middle"><img src="https://github.com/niki914/agentic-nexus/blob/main/res/hyper.gif?raw=true" alt="hyper" width="200"/></td>
<td align="center" valign="middle"><img src="https://github.com/niki914/agentic-nexus/blob/main/res/magisk.gif?raw=true" alt="magisk" width="200"/></td>
</tr>
</table>

Nexus 把你每天都在用的那个语音助手交回到你手里: 你来决定它用哪个模型、怎么思考、能做什么。它不再是一个只会查天气、定闹钟的固定程序, 而是一个真正属于你的 Agent——你说一句话, 它就能调动整台手机替你完成

> 当前仍处于 Beta 阶段, 能力与稳定性会持续打磨。源码暂不公开

### 它为什么值得一试

它是一个 Agent, 普通的 AI 应用止步于"换个模型回答"。Nexus 在做的事不同——

<div align="center">
  <img src="https://github.com/niki914/agentic-nexus/blob/main/res/custom_tool_using__long.jpg?raw=true" alt="custom_tool_using" width="200"/>
</div>

- **Skills 系统**: Agent 应用的敲门砖——为你的助手带来更多种可能性
- **MCP**: 自由加入各种工具组，Notion、GitHub、HomeKit...资源决定上限
- **接管规则**: 你定义 Agent 什么时候该出手接管、什么时候该让系统助手来调闹钟
- **记忆系统**: 记住你的偏好, 换一段新对话也不会忘
- **会话历史**: 浏览、复刻任何一段对话。Agent 的每一次成长都有迹可循

**它和 Android 真正长在一起**

这才是 Nexus 跟 ChatBot 拉开距离的地方——

- **终端, 三个梯度**: 普通应用权限 -> [Shizuku](https://github.com/rikkaapps/shizuku) -> Root
- **SSH, 打通两个世界**: 连上 [Termux](https://github.com/termux/termux-app), 你的 Android 就有了 Linux 环境；连上开发机, Agent 就能操作你的工作站

但最关键的甚至不是这些能力本身, 而是: **你不需要懂终端**。告诉 Agent 你要做什么, 它自己写命令、自己执行、自己处理结果。你要的只是结果

这带来的终极玩法: **连接 [claude-code](https://github.com/anthropics/claude-code) 做无头 AI Coding**——Agent 在你手机上, 帮你命令 cc 干活, 代码跑在远端, 一句话搞定。懂的都懂

### 技术基础

AI 对话 & Tooling: [s3ss10n](https://github.com/niki914/s3ss10n)
UI: Material3E X [Liquid Glass](https://github.com/Kyant0/AndroidLiquidGlass)
Shell & SSH 终端能力: [libterm](https://github.com/niki914/libterm)

### 兼容性

- 已适配主流厂商的语音助手（小布、小爱）, 更多适配持续进行中
- 当当前设备暂不支持接管时, 仍可使用 Nexus 内置的对话界面
- 支持 DeepSeek、OpenAI、Anthropic、Google 等模型, 也可填写兼容接口
- 自带模型服务的 API Key 即可使用, Nexus 不内置模型账号

### 反馈

反馈时请尽量包含: 

- 手机型号与系统版本
- 使用的语音助手类型
- 助手版本号与 Nexus 版本号
- 复现步骤、截图或录屏
- 如果可用, 附上相关日志

社区与反馈入口以应用内"关于"页面展示为准
