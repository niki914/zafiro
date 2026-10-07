# About

## 项目简介 - 详见 README.md

---

## 项目知识

### 仓库本体

```
./
├── .agents/skills/      # SKILLs 真实目录
├── .claude/skills/      # .agents/skills 的软链，不用修改这里的文件
├── .githooks/           # 提交前坏味道检查；规则、放行、清理方式见其 README.md
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

### MVI 架构

项目强制使用 MVI 心智：UI 只发意图、只读 VM 的 State，VM 通过更新 State 驱动 UI。基类见 ui-kit 的 ComposeMVIViewModel

State 与 Effect 的区别是持久与一次性，分界的判据是「换个时间再读还成立吗」：列表数据、弹窗开关属于 State；Toast、聚焦、退出页面属于 Effect，只发生一次、没接住就没了

弹窗最容易误判：触发弹窗是一次动作，但「弹窗开着」是界面当前的样子，重组、旋转、被压栈后回来都要能读到同一个答案，所以它是 State

### 宿主 / Host

本项目通过 LSPosed 对 com.heytap.speechassist 等应用做 Hook，将系统语音助手的回答换成 Zafiro Agent 回答，具体实现是通过 Binder + AgentRuntimeService。当前的实现重点是，复杂的数据结构从不穿过 Binder，IPC 传递纯语义数据（Thinking、Tools、Content）。宿主架构采用分层治理：上层 `*Hook`（如 `BreenoHook`）为纯胶水调度层，严禁堆砌反射与类名硬编码

我们把取代原生 agent 的功能称为 takeover / 接管。宿主是比较边缘的业务，在重要决策时，不应该为了宿主的业务而去妥协，应该牺牲宿主

### 主界面 / Home / Compose

这些都是指应用的主入口，Compose UI，具体的对话是在 HomChatViewModel 内通过 Agent API 维护。Compose 必须是无状态的，从配套的 MVI ViewModel 里取数据

这是整个应用的根基，它的重要性和优先级最高

### UI 框架 / ui-kit

导航、导航栏、弹窗、设置项都用 ui-kit 里既定的那套，不要另起一套。实现涉及导航、ViewModel、弹窗的业务前 MUST 调研已有代码作为参考

#### 导航

导航是自行实现的（ui-kit 的 nav 包），而不是使用 nav3。每个 entry 自带 viewmodel 生命周期，页面 VM 要通过框架拿，不要自己去取

push 分正推与从左推（onPushFromLeft）两个方向，返回要配对使用；返回按钮的位置也跟着方向走，正向推入在左上角，从左推入在右上角

#### 导航栏与左右上角按钮

导航栏指 LiquidScreen 顶部的动作条，只有左右两个图标槽，内容由页面通过 PageChromeContribution 覆盖，页面不自己画

背景色与标题由滚动状态自行感知切换，大部分页面不需要参与；左右按钮通常指这两个图标槽

#### 弹窗类

约定使用 LiquidDialog（ConfirmationLiquidDialog、SingleChoiceLiquidDialog 等），它必须挂在 LiquidScreen 的内容树里，返回键由弹窗自己接管

BottomSheet 用 OptionSheet

#### 设置项

设置页、设置列表、设置详情统一用 ui-kit 的 Settings* 组件（页面骨架、分组卡片、行、分隔线都有现成的），如无明确要求，禁止手搓

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

## HARD GATE | MUST FOLLOW

### 共识

- 若某项功能实现难度高（难度评分超过 6/10）且 ROI 较低，在着手开发前应先与用户协商功能范围
- 没有明确要求提交时，不提交。等待用户验收
- 提交信息和 PR 标题均使用英文，采用 `feat: did something` 这样的格式；标题简洁明了，不带模块名，补充说明写在正文中
- 未经允许禁止对做安卓做安装应用等写操作
- 搜索用 `rg`，不要 `grep -r`。 `rg` 默认读 `.gitignore`，自动排除 `**/build/`；`grep -r` 会扫到 build 与 `.git`

### 架构决策

- 架构决策应着眼于长远。不要接受那种仅能暂时应付、日后还需替换的 Workaround
- 拒绝处理人工操作无法复现（诸如在几十毫秒内迅速点击的、高手速要求的操作）的并发/竞争类 issue，这类问题 ROI 极低，且导致过度防御和复杂化
- 不必维持向后兼容性。移除过时的路径，而不是添加兼容层、回退机制或迁移逻辑
- 采用能完全满足当前需求的、最简单的实现方案。避免过度抽象、过多的配置项以及不必要的间接层
- 采用分层方式构建系统。从能实现端到端功能的最小版本起步，在现有可用产品的基础上逐步增加新功能。切勿为了尚未完成的复杂设计而牺牲现有的可用产品
- 在开辟大型业务时，优先考虑用 ServiceRegistry 来做依赖注入，避免在构造函数、方法签名里面堆砌太多字段
- 若能降低整体复杂度或提高可靠性，应优先使用成熟且维护良好的现有库。除非有充分理由，否则不要重复实现通用功能
- 在自行编写实现或引入新包之前，应优先利用项目中已有的依赖项。在未查阅文档和类型定义之前，切勿主观臆断某个库不具备某项功能

### 实现代码

- 坏味道由 `.githooks/` 在提交前拦（监视器锁、变化叙述注释、内联全限定名、绕过 Logger 的日志、日志 TAG 前缀）；规则、放行标记、按模块清理方式见 `.githooks/README.md`。需要提交时，无须提前阅读 Hooks，正常提交即可，有问题会被 Hooks 扫出
- 不要使用后台任务进行编译或单测，使用同步方法
- 禁止出现 [改两行 ui 字符串 -> 编译 -> 再改] 的行为，应该在确实需要时（比如做了重型重构后）编译
- 实现多语言时需通过 ls 等手段确认实际的语言种类

### 单测

- 写或修改任何单测之前，**必须先调用 `test-triage` skill**：先判断这个测试该不该存在（拦截力），通过了再写。单测规则以该 skill 为准

### requireService<>()

- 此方法正是为了做依赖控制，应直接在函数体或成员变量处调用，必须避免在构造函数或者方法签名里面使用
- 典型的场景是传递 Context 或 Application，这类需求完全可以通过 Service 来实现
- 当新/旧业务适合通过一个简洁调用面提供出来并被广泛使用时，应考虑封装为一个 Service

---

## Preferred Skills

### 认知对齐

不仅在与用户对话时会有误差，与 subagent 打交道时同样会出现误差，误差的后果是，后者做出来的东西并不符合前者的预期，因此描述任务的人必须尽可能确保他们的任务一清二楚

- 在一个需求开始时，如果用户没有带着详细的计划，优先通过 `grill-me` 或 `grill-with-docs` 来快速与用户对齐认知
- 在需要派发 subagent 的任务中，通过 `prompt-engineering` 或 `writing-for-agents` 来与它们对齐

### 讲解

- 需要向用户阐述复杂内容时，可以通过 `eli5` 或 `show-me` 帮助解答。eli5 是打比方，show-me 是用前端页面
