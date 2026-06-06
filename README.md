# Nexus

> 让手机里的语音助手，接入你自己的模型，并真正开始替你做事

[![stars](https://img.shields.io/github/stars/niki914/agentic-nexus?label=stars)](https://github.com/niki914/agentic-nexus)
[![release](https://img.shields.io/github/v/release/Xposed-Modules-Repo/com.niki.breeno.openai?include_prereleases)](https://github.com/niki914/agentic-nexus)
[![downloads](https://img.shields.io/github/downloads/Xposed-Modules-Repo/com.niki.breeno.openai/total)](https://github.com/niki914/agentic-nexus)

<div align="center">
  <img src="https://github.com/niki914/agentic-nexus/blob/main/raw/using_mcp.jpg?raw=true" alt="using" width="200"/>
  <img src="https://github.com/niki914/agentic-nexus/blob/main/raw/memory.jpg?raw=true" alt="memory" width="200"/>
  <img src="https://github.com/niki914/agentic-nexus/blob/main/raw/mcp_status.jpg?raw=true" alt="status" width="200"/>
</div>

Nexus 把你每天都在用的那个语音助手交回到你手里: 你来决定它用哪个模型、怎么思考、能做什么。它不再是一个只会查天气、定闹钟的固定程序，而是一个真正属于你的智能体——你说一句话，它就能调动整台手机替你完成

> 当前仍处于 Beta 阶段，能力与稳定性会持续打磨。源码暂不公开

<div align="center">
  <img src="https://github.com/https://github.com/niki914/agentic-nexus/blob/main/res/full.gif?raw=true" alt="full" width="600"/>
</div>

### 它为什么值得一试

- 一套足够好看的界面: 采用 Apple Liquid Glass 融合 Material3 的设计语言，简约、克制、优雅，从引导到对话再到设置，每一屏都经得起细看
- 模型由你做主: 自由选择模型与服务，原本被厂商写死的助手能力，变成可以随心替换的个人配置
- 不止于聊天: 内置记忆、工具调用与扩展能力，让助手从"能回答"进化到"能动手"
- 门槛足够低: 核心体验不绑定复杂前置条件，不折腾也能用起来

### 真正的看点: 让一句话调动整台手机

普通的换源工具止步于"换个模型回答"。Nexus 想做的是把 Agent 和安卓系统能力连起来——通过 Shell 与自定义工具，你的手机能玩出花

- 打开指定 App: 一句"帮我打开记账本"，助手直接拉起应用
- 跳转指定网站: 把常去的页面交给助手，张口即达
- 运行你的脚本: 让 Agent 用 cat 帮你写好脚本，再封装成一个工具，例如 `bash /sdcard/backup.sh`，以后一句话触发
- 发送应用通知: 任务跑完，主动推条通知提醒你

再往上一层，是 MCP: 想象你接入了米家 MCP，加上一台已 Root 的手机——"把客厅灯调暗，空调开到 26 度"，语音助手就替你全做了。接入越多 MCP，你的助手能触达的世界就越大

### 工具与记忆

Nexus 不只是一层模型转发，它内置了一套运行时工具系统

- 记忆: 记住你的偏好与重要信息，换一段新对话也不会忘
- 内置工具: 管理预置的基础能力开关
- 自定义工具: 把固定命令封装成助手可理解、可调用的工具
- MCP: 接入外部服务，自动发现并使用更多能力

> 命令执行类能力很强，也意味着风险，请只添加你理解且信任的命令

<div align="center">
  <img src="https://github.com/niki914/agentic-nexus/blob/main/raw/custom_tool_using__long.jpg?raw=true" alt="long" width="200"/>
</div>

### 兼容性

- 已适配主流厂商的语音助手 (如小布、小爱)，更多适配持续进行中
- 当当前设备暂不支持接管时，仍可使用 Nexus 内置的对话界面
- 支持 DeepSeek、OpenAI、Anthropic、Google 等模型，也可填写兼容接口
- 自带模型服务的 API Key 即可使用，Nexus 不内置模型账号

### 常见问题

#### Nexus 是聊天 App 吗

它有一个很好用的内置聊天界面，但核心目标是让你日常的语音助手接入你自己的模型，并替你做事

#### 支持所有机型和版本吗

不承诺。系统助手的内部实现会随版本变化，适配按机型与版本逐步推进，未覆盖的版本可能体验受限

#### 会内置模型或代理服务吗

不会。你需要自行准备模型服务、API Key 和网络环境

#### 项目会开源吗

暂不公开源码。本页面用于介绍功能、收集反馈与发布使用说明

### 反馈

反馈时请尽量包含

- 手机型号与系统版本
- 使用的语音助手类型
- 助手版本号与 Nexus 版本号
- 复现步骤、截图或录屏
- 如果可用，附上相关日志

社区与反馈入口以应用内"关于"页面展示为准
