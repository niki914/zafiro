# About

## 项目简介

详见 README.md

---

## 术语

### 宿主 / Host

本项目通过 Xposed API 对 com.heytap.speechassist 等应用做 Hook，将系统语音助手的回答换成 Zafiro Agent 回答，具体实现是通过 Binder + AgentRuntimeService。当前的实现重点是，复杂的数据结构从不穿过 Binder，即，复杂的数据结构留在主进程，在 map 后喂给宿主，而宿主侧 Binder Client 得到纯文本，直接无脑 render，通过这种方式，我们降低了在数据库所需要投入的成本

我们把取代原生 agent 的功能称为 takeover / 接管。宿主是比较边缘的业务，在重要决策时，不应该为了宿主的业务而去妥协，应该牺牲宿主

### 主界面 / Home / Compose

这些都是指应用的主入口，Compose UI，具体的对话是在 HomChatViewModel 内通过 Agent API 维护

这是整个应用的根基，它的重要性和优先级最高

### 对话列表

AI 对话通常是以 list 的形式存放 messages，同时只会有一个对话在进行，数据结构：Conversation - List<Message>。应用通过 Room 数据库，将每次 AI 对话的内容持久化。对话列表指的是对话的列表，数据结构：ConversationList - List<Conversation>

这是一个不应混淆的概念，通常在提及对话列表的时候，就是在指后者，而不是 HomeChat 对话界面

### AI 对话数据结构

- Conversation - 单次对话中的消息的集合
- Turn - Agent 通常会与 Tool 交互多个回合才结束。因此，我们将一条用户消息与其产生的若干 Agent 回答、Tool 调用、Tool 结果的集合称为一个 Turn。便于管理

### 持久化

项目有着多种持久化方案：

- 维护对话列表：使用 Room 数据库，
- 其他：通过在沙箱内直接读写文件实现，主要通过多 Json 单元（见 XRepo API）

### IPC

目前来说，agent 的对话功能是限定在主进程之内的，虽然有一个宿主的业务，Agent 相关的调用逻辑依然用 Binder 包裹在主进程之内

项目通过 Chaquopy 实现 Python 能力支持，在单独的 py 进程中运行代码

---

## HARD GATE

- 不必维持向后兼容性。移除过时的路径，而不是添加兼容层、回退机制或迁移逻辑
- 采用能完全满足当前需求的、最简单的实现方案。避免过度抽象、过多的配置项以及不必要的间接层
- 采用分层方式构建系统。从能实现端到端功能的最小版本起步，在现有可用产品的基础上逐步增加新功能。切勿为了尚未完成的复杂设计而牺牲现有的可用产品
- 在开辟大型业务时，优先考虑用 ServiceManager 来做依赖注入，避免在构造函数、方法签名里面堆砌太多字段
- 若能降低整体复杂度或提高可靠性，应优先使用成熟且维护良好的现有库。除非有充分理由，否则不要重复实现通用功能
- 在自行编写实现或引入新包之前，应优先利用项目中已有的依赖项。在未查阅文档和类型定义之前，切勿主观臆断某个库不具备某项功能
- 架构决策应着眼于长远。不要接受那种仅能暂时应付、日后还需替换的权宜之计
- 若某项功能实现难度高（难度评分超过 6/10）且 ROI 较低，在着手开发前应先与用户协商功能范围
- 允许对 UI 相关的状态机做测试，但禁止给 UI 写单元测试
- 提交信息和 PR 标题均使用英文，采用 `feat: did something` 这样的格式；标题简洁明了，不带模块名，补充说明写在正文中

---

## Preferred Skills

### 认知对齐

不仅在与用户对话时会有误差，与 subagent 打交道时同样会出现误差，误差的后果是，后者做出来的东西并不符合前者的预期，因此描述任务的人必须尽可能确保他们的任务一清二楚

- 在一个需求开始时，如果用户没有带着详细的计划，优先通过 `grill-me` 或 `grill-with-docs` 来快速与用户对齐认知
- 在需要派发 subagent 的任务中，通过 `prompt-engineering` 或 `writing-for-agents` 来与它们对齐

### 讲解

- 需要向用户阐述复杂内容时，可以通过 `eli5` 或 `show-me` 帮助解答。eli5 是打比方，show-me 是用前端页面

---

## 未完成项目

[] HARD: 将 LLMController、agent-runtime 这样的遗留代码逐渐迁移成便于维护和解耦的 ServiceManager 模式
[] MEDIUM: 统一整个项目的权限管理（:libs:permission-manager)，这个模块封装了一套权限管理系统，实现了 Root-Shizuku-Dialog-JumpSetting 的降级方案，有待确认的是，是否整个项目都完全通过这个 API 做权限获取
[] MEDIUM: 统一 :libs:permission-manager 与 :libs:libterm 中 Root、Szk 相关的重复代码
[] EAZY: 通过 Agent API 构建出其他的 Agent 状态载体 - A - 常驻通知栏
[] MEDIUM: 通过 Agent API 构建出其他的 Agent 状态载体 - B - 悬浮球
[] MEDIUM: 通过参考开源项目重构宿主业务
[] HARD: 实现一个 Replay 功能，用户可以录制一段操作，作为工具保存下来，Agent 通过调用这个工具来重放用户的操作。此任务依赖于悬浮球