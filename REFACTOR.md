# REFACTOR · LLM 会话门面重构

本文供接手本次重构的 AI 阅读。目标是把「LLMController 上帝类 → 干净的门面 + 依赖控制」这件事的阶段边界、前因后果与判定标准写清楚，避免接手者重新推导一遍。

## 0. 一句话与硬约束

**要做的事**：为对话业务重新设计一个门面（`business:api`），业务方只依赖接口；用一个进程内服务注册表（`iservice`）做依赖控制；然后把 `LLMController` 的内部实现一层层架空、替换、删除。

**硬约束**（违反即返工）：

1. `business:api` 不得依赖 `libs:okia`、Android / androidx、以及任何实现模块。只依赖 coroutines。
2. `app` 模块内除「组合根」与「委托实现适配器」外，代码不得出现 `LLMController.` 调用。
3. 契约里不得有第二条输出通道：没有 `Flow` 返回值、没有回调参数、没有 getter。状态只能订阅，只能经命令改。
4. 委托实现阶段的适配器是**唯一允许的临时兼容层**，必须在阶段 5 删除（`CLAUDE.md` 禁止长期兼容层）。
5. 阶段 2、3 不改行为；改行为的只有阶段 4、5。
6. 一个 PR 只做一件事。前次失败的直接原因是单次提交过大（见 1.3）。

## 1. 前因后果

### 1.1 触发点（用户原话）

> 我在梳理 LLM Controller 我目前有几个代办：依赖注入、上帝类解耦、能力依赖梳理…… 我新增的两个需求（还没做）是常驻通知和悬浮窗，这两个不想 HomeChatViewModel 一样依赖这么多，它们大体上会依赖 AgentStatus 以及简单的 Stop 能力。
>
> 重要的一点是同时只会有一个对话在进行中（无论有几个 Client，Client 就是 Compose、通知栏、悬浮窗这种 Agent 信息的载体），然而多个 Client 如果都具备写的能力那必然会打架，而 LLMCtrl 的颗粒度显然已经太大（这个 object 从项目初期就有了，一直没有重新审视过）。
>
> 我需要一个清晰的结构，无论是 Service 还是啥都行，因为很显然我们的业务正在往后台运行这个方向发展。

### 1.2 现状（已核实的事实）

`agent-runtime/src/main/java/com/niki914/zafiro/chat/LLMController.kt`：938 行，`object` 单例，10 组职责混在一起（配置与提示词装配、图片 ingest、okia 实例与协议装配、会话生命周期、回合执行、停止、本地工具注册、MCP 发现、历史修复钩子、杂项状态）。

**执行所有权分散**：

- 发起回合有两处，门禁互不可见：Compose 直调 `LLMController.stream`（`HomeChatState.kt:226`），自用 UI 状态 `isGenerating` 判断；宿主经 `Service.submit`，用 `activeTurn` 的 CAS 判断（`AgentRuntimeService.kt:141`）。
- 唯一兜底是 OKIA 的并发契约：活跃回合中二次 `send` 抛异常，被映射成 `TurnConflict` 错误卡（`LLMController.kt:928`）。也就是说两个客户端同时发起时，表现是一条错误。
- `turnActive`（`:901`）只喂 `keepScreenOn`，不参与拦截。
- `stop` 有 4 个入口、`reset` 有 2 个入口，全部无条件转发。

**同一份事件流被折叠三次**（`app/src/main` 内 `LlmStreamEvent` 引用数）：

| 折叠点 | 引用数 | 产出的状态 |
|---|---|---|
| `HomeChatState.kt` | 35 | Compose 的 `HomeChatUiState` |
| `LlmStreamCollectors.kt` | 32 | 宿主渲染帧 `RenderFrame` |
| `AgentRuntimeService.kt` | 2 | 错误文本本地化 |

加上 `AgentStatusHolder` 折出的通知状态，同一个回合有 4 份状态各自演化，一致性靠人工对齐。`AgentStatusHolder`（PR #229）在 main 上**只有写入方、没有生产读者**。

### 1.3 前次尝试的教训

`feat/global-conversation-state`（worktree `/Users/niki/.repo/android/zafiro-worktrees/global-conversation-state`，HEAD `ca81b878`，落后 origin/main 10 个提交）做过同一件事，未合入：

- 新增 `agent-runtime/.../chat/runtime/` 五个文件：`ConversationStateReducer` 751 行、`ConversationSnapshot` 482 行、`ConversationExecutor` 426 行、`ConversationPermissionObserver` 345 行、`ConversationRuntime` 189 行。
- 相对 merge-base 的 diff：35 文件、+8437 / −422。
- `review.md` 结论 `needs-fix`，卡点是授权上下文传播与 stop 竞争这类行为语义。
- main 上没有 `chat/runtime/`。

结论：抽象的方向对，形态过重。本轮的策略是「小步、门面先行、实现后置」。

### 1.4 解耦方式（用户原话）

> 我之前不是在大厂实习过吗？里面有一个 Iservice。它是用来做模块间解耦的。它的用法很简单：`ServiceManager.get<IXXXInterface>()?.doXXX()`。这绝对是一个绝佳的解耦方式。这样的话，我们让 LLM controller 变成一个 `class A : B, C, D`，B、C、D 分别是我们要解耦的不同的能力，全都用接口的形式把它解开了。但是实际上，他们在整个应用的各个进程里面都指向相同的对象。
>
> 别跟我讲你没看过大厂的…… 它都是 `<T>`。用 Key 的话，就没有那么类型安全了。

**已采纳的部分**：接口按能力切分 + reified 类型查找（`get<T>()`），已落地在 `agent-runtime/src/main/java/com/niki914/zafiro/service/ServiceRegistry.kt`。

**已纠正的部分**：`get<T>()` 返回本进程对象，跨进程不成立。正确表述是「同一接口，每进程一份实现」：主进程实现是会话本体，宿主实现是 Binder 代理（仓内已有先例：`Entrance.kt:60` 用 `XIpcDomainSettingsStore(client)` 换掉 `XRepo` 的 store 实现）。

### 1.5 为什么不引 DI 框架

仓内 131 个纯 JVM 单测（JUnit4 + mockito + coroutines-test）；`agent-runtime` 无注解处理器（KSP 只配在 `app`，供 Room 用）；Hilt 需要 Application 注解与生成组件，JVM 单测还要加测试规则。注册表本身即 DI 机制，再叠框架会出现两套取依赖方式。组合根按进程划分：主进程 `App.onCreate`，宿主进程 `Entrance.onLoad`。

## 2. 门面设计：判据与形状

### 2.1 MVI 判据（用户原话）

> 你重新审视一下，到底什么是 MVI，你就会明白为什么我会这么去设计。这样设计有一个好处是，无论谁是那个发送指令的客户端，不会出现只有一方能监听到结果的这种问题。

MVI 三条规则里第三条决定全部形状：**状态是唯一的输出通道**。任何「直接回给调用者的返回值 / 回调 / 事件流」都会开出第二条输出通道，结果是「谁发起谁收」。因此：

- `stream()` 不返回回合句柄（无 ID / Job / Flow / Deferred）。结果落进状态。
- 命令的参数是允许的（`updateDraft`、`load(sessionId)`），参数不是输出通道。
- 没有 getter：状态只能订阅。
- 派生字段（`isGenerating`、`keepScreenOn`）不进状态，从 `phase` 判断。

### 2.2 当前形状（`business:api`）

```
business/api/src/main/java/com/niki914/zafiro/api/
├── Agent.kt                        `Agent`：`conversation` / `draft` / `updateDraft` / `clearDraft` / `stream` / `load` / `discard` + `TurnStart`
├── AgentControl.kt                 `AgentControl`：`status` / `stop` / `addApprover` / `removeApprover`
├── Approver.kt                     `Approver`：`decide(request)`（消费方实现）
└── model/
    ├── AgentStatus.kt              `AgentStatus` + `AgentPhase` + `TurnOutcome`
    ├── ApprovalRequest.kt          `ApprovalRequest` + `ApprovalDecision`
    ├── Attachment.kt               `Attachment`
    ├── Conversation.kt             `ConversationId` / `Conversation` / `TurnId` / `ConversationTurn`
    ├── Draft.kt                    `Draft` + `DraftImage`（`Pending` / `Ready`）
    ├── ToolInvocation.kt           `ToolInvocation` + `ToolOutcome`
    └── TurnBlock.kt                `TurnBlock`（5 种形态）+ `TurnFailureCode`
```

分档（按客户端实际需要，宽接口继承窄接口）：

| 档 | 接口 | 消费方 |
|---|---|---|
| 窄 | `AgentControl` | 悬浮窗、常驻通知、MainActivity |
| 宽 | `Agent`（继承 `AgentControl`） | Compose、宿主 |

分档目的：让「不该调的命令」在编译期不可见。

### 2.3 三条判定规则

**规则 1 · 契约只收被调用点命名过的类型。** 用扫描核对，不靠估（`business/api/SCAN.md` 第三节逐条列了删除理由）。

**规则 2 · 输入是状态，不是命令参数。** 用户原话：

> 对于 draft 和 part 的设定，我是根据我们的对话 UI 来设计的，因为我觉得输入框里面有文本和有图片是一件很正常的事情。

草稿属于 agent 的状态，因此多客户端共享同一份文本；`stream()` 读草稿与草稿更新落在同一次归约上，没有「读草稿 → 别人改 → 发起」的竞态。草稿的写入口只有一个（`updateDraft`，同步纯变换）；异步落盘用 `DraftImage.Pending` 表达，实现侧观察到它再归约成 `Ready`。消费方按 `Pending.uri` / `Ready.attachment.path` 去重与移除，不另设 id。

**规则 3 · 多来源用注册，不用中心命令。** 用户原话：

> permission responder 这个东西，其实弄成那种 add listener 的机制可能会比较好一点…… 我希望可以有多个来源去监听到这种情况，然后再授权…… 我还是希望做成接口注册，而不是直接传 Lambda。

授权来源（Compose 对话框、overlay 弹窗、通知按钮）各自实现 `Approver` 并注册；按注册顺序询问，`ApprovalDecision.Abstain` 交给下一个，全部弃权或没有来源即拒绝。

## 3. 阶段边界

用户原话（阶段划分的出处）：

> 我们先把门面打好，然后堆屎，何谓堆屎？那就是表面上业务调用的是 interface Agent，但其实它的 impl 只不过是委托给 llmcontroller 或者 runtime or Whatever…… 后面业务方干净了，我们就去把这些遗留的业务实现层给重构了。

以下「委托实现」即原话的「堆屎」阶段。

| 阶段 | 交付物 | 允许改的 | 禁止 | 验收判据 |
|---|---|---|---|---|
| 0 已完成 | 消费面扫描 + `business/api/SCAN.md` | — | — | 调用面穷举可核对 |
| 1 进行中 | 门面定稿：`business:api` 的类型、接口、注释 | `business/api/**` | 改任何现有实现 | `./gradlew :business:api:clean :business:api:assemble` 通过；`compileClasspath` 只有 coroutines 与 stdlib |
| 2 委托实现 | `Agent` 的实现类，内部转发到 `LLMController`；业务方切到门面（宿主本轮不动） | 新增适配器文件；调用点接线 | 改 `LLMController` 内部；改行为 | 除适配器与组合根外，`app` 内无 `LLMController.`；`App.onCreate` 装配注册表 |
| 2a | 归约器移到实现侧（`HomeChatState` 的 `applyEvent` 折叠搬走） | 实现侧、Compose 侧渲染 | 改状态语义 | Compose 只渲染，不再折叠事件 |
| 3 架空 LLMController | 按能力逐个抽出服务：settings 网关、py、工具注册表、MCP 调度、会话存储、图片编码 | 每个 PR 抽一个 | 一次抽多个 | `LLMController` 只剩 `requireService<T>()` 取协作者 |
| 4 真源切换 | 内容真源移到 `Agent.conversation`；status / 宿主渲染都从它派生（persister 继续观察引擎树） | 行为 | 保留旧真源 | 删除 `LLMController.currentConversation` 出口 |
| 5 删旧 | 删适配器、旧事件线、`AgentStatusHolder` 的重复折叠 | 行为 | 保留兼容层 | `grep -rln "import com.niki914.okia" app/src/main` 返回空 |

阶段 3 是大头，按能力切多个 PR；每个 PR 的规模目标是「评审半小时内能看完调用点从哪改到哪」。

## 4. 已核实的事实清单

接手者不必重新查证以下内容。

**okia 语义**（`libs/okia/src/main/java/com/niki914/okia/Okia.kt`）：

- `val conversation: StateFlow<Conversation>` —— 实例内存活一棵对话树（`history` + `leafId` + `live`）。
- `suspend fun export(): SessionSnapshot` —— 注释写明 host 自行决定存储位置，恢复走 `open(restore)`。
- 注释：「无 fork：分支由下游 `export()` + `open(restore)` 自行实现，库只维护单棵对话树」。
- 引擎不落盘；持久化是 `ConversationPersister` 观察 `currentConversation` 增量写 Room。
- 并发契约：活跃回合存在时 `send` 与任何改会话状态的操作都抛异常，`stop` 是唯一例外。

**`resetConversation()` 的真实行为**（`LLMController.kt:584`）：`PyRuntime.kill()` + `TerminalSessionPool.closeAll()`（杀工具资源）→ `okia.close()` → 置空 `okia` / `sessionProtocol` / `conversationFlow`。**不新建实例**（下次 `ensureSession()` 惰性建），**不动 Room 记录**（删历史是 `ConversationRepo.deleteConversation` 的事）。容易被误读成「清空历史」。

**`reset` 不只主 UI 在用**：`AbstractAssistantHook.onSessionReset()` → `textSource.resetConversation()` → Binder → `AgentRuntimeService` → `LLMController`（`AbstractAssistantHook.kt:112`）。因此它需要保留，但不应暴露给通知与悬浮窗。

**生产调用面**（`LLMController` → 门面）：

| 调用方 | 成员 | 去处 |
|---|---|---|
| `HomeChatViewModel` | `stream` / `stopCurrentRound` / `resetConversation` / `ensureSession` / `openSession` / `historySnapshot` / `ingestUserImage` / `currentConversation` / `keepScreenOn` | `stream()` / `stop()` / `discard()` / 实现内部 / `load()` / fork 定位用（未进契约） / `DraftImage.Pending` / 流式归约的输入 / `status.phase` |
| `MainActivity` | `keepScreenOn` | `status.phase != Idle` |
| `ConversationPersister` | `currentConversation` | 待定（见第 6 节） |
| `AgentRuntimeService` | `stream` / `stopCurrentRound` / `resetConversation` | 同上（经 Binder） |

无生产调用方（只在单测出现）：`refresh`、`refreshFromHookContext`、`snapshot`、`toolRegistry`、`okia`、`okiaFactory`、`resetForTest`，以及 `LlmRuntimeSnapshot` / `ResolvedLlmConfig` / `ResolvedTools` 等 8 个 data class。这些留在实现侧，不进契约。

**扫描脚本**：`/tmp/zafiro-scan/scan.py`（覆盖 `app`、`agent-runtime`、`xposed-*`、`ui-kit`、`store`、`libs:okia` 的 main 源码；输出每个 LLMController 成员的调用点与所在函数、每个事件的消费者、模型字段的读取点）。契约改动前用它核对「有没有生产消费方」。

**既有接缝**：`ServiceRegistry`（`agent-runtime/.../service/ServiceRegistry.kt`，reified `installService` / `requireService`）、`RuntimeEnvironment`（`install` / `awaitBridge` / `requireBridge` / `clearForTest`）、`HomeChatRuntime`（`HomeChatState.kt:207`，构造注入）、`ConversationPersister.start(scope, source)`、`XRepo.init(ctx, store)` + `installStoreForTest`。

## 5. 当前进度

**分支策略**：`dev` 从 `origin/main = 10c333d6` 切出；重构的每个 PR 从 `dev` 切出、合回 `dev`；最终验收完 `dev` 再合进 main。`dev` 只跑这一系列，main 主线的杂活仍然直接切分支合 main。

**当前工作区**：分支 `refactor/conversation-session-architecture` 下只有 `business:api` 进第一个 PR；其余未提交改动（`HomeChatRuntime` 接线 5 文件、`ServiceRegistry`、业务测试）都不进本 PR。

**已落地**：

| 内容 | 路径 |
|---|---|
| 门面模块（10 个源文件，631 行；`assemble` 通过，`compileClasspath` 只有 coroutines 与 stdlib） | `business/api/**`（`settings.gradle.kts` 已 `include(":business:api")`） |
| 扫描结论与调用面映射 | `business/api/SCAN.md` |
| 注册表（reified 类型查找） | `agent-runtime/src/main/java/com/niki914/zafiro/service/ServiceRegistry.kt` + 单测 |
| ~~调用点接线（`HomeChatRuntime` 补 `currentConversation` / `keepScreenOn`，四处调用方改走注册表）~~ | 废弃：那是第二套门面，不再维护。业务方从下一个 PR 起直接接 `Agent` |
| debug 包 | `/Users/niki/.repo/android/agentic-nexus/app/build/outputs/apk/debug/app-debug.apk`（57,399,720 字节） |

`app` 模块内 `LLMController.` 现在只剩 `LlmHomeChatRuntime` 这个适配器（`HomeChatState.kt:235`）与 `App.kt` 的装配注释。

## 6. 待决问题

门面的六条批评已有结论：

1. **文件与包组织**：已按「一个文件一个接口，业务接近的 data class 同文件」重排（`api/` + `api/model/`）。
2. **接口命名要考虑调用面**：已定 `Agent` / `AgentControl` / `Approver`，按调用点的读法验收。
3. **`stream()` 的返回值**：已定 `TurnStart`（`Started` / `DraftEmpty` / `Busy`）。命令返回值不是输出通道。
4. **宿主的一次性 query**：用户已否决单独命令，宿主走 draft（`updateDraft` 写入提问 + `stream()`）。
5. **`overview`**：已删除，`phase` 压进 `AgentStatus`。
6. **`approval` 是否重复**：已处理，`AgentStatus` 不含待授权请求，授权走 `Approver` 注册。

不进阶段 1 的架构问题（`SCAN.md` 第四节同源，落点未定，改动前先定）：

1. **消息派生操作（regenerate / fork / rewind）的落点**：现在由 `HomeChatViewModel` 自己算用户消息下标（`findUserTurnIndex` / `findNextUserIndex`）再调 `ConversationRepo.forkConversation`。这段下标算术属业务，但落点未定。
2. **会话内容的持久化观察**：`ConversationPersister` 继续观察引擎的会话树；Room schema 与存量数据不变；`conversation` 不承载提交边界。
3. **宿主**：一次性 query 与草稿的关系未定，阶段 1 不碰。
4. **草稿持久化**：仍由 app 侧按按键节流写 Room 的 `draft_text` 列，草稿图片不进 Room。
5. **授权多来源的命中策略**：优先级询问 + 弃权（当前设计），还是并发询问 + 先到先得。
6. **事件折叠与节流**：`applyEvent` 与 `TextPacer` 本轮留在 ViewModel。折叠进实现侧时，`conversation` 的发射频率由实现决定（打字机靠「最后一轮的文本块在变」）。
7. **通知文案的归属**：失败文案进 `ToolOutcome.Failed.message` 后的多语言归属未定，本轮不动。

## 7. 模糊场景怎么办

- **新增字段前**：跑扫描，确认有生产消费方。没有就写在 `SCAN.md` 的「删除项」里，不进契约。
- **命名拿不准**：按调用面命名（谁会调这个接口），不按实现命名。看一眼调用点的读法是否顺口。
- **类型数量膨胀**：先问能不能用已有类型表达（`String` / okia 之外的既有类型）。单字段包装类型优先合并。
- **契约要动现有实现才能落地**：停下来问用户，不要顺手改行为。改行为的窗口只有阶段 4、5。
- **发现前次 `chat/runtime/` 里的现成代码**：可以读它作为参考（reducer / snapshot / executor / permission observer 的设计思路值得看），但不要整体迁移——它的规模是本次诉求的数倍。
- **用户提出与本文冲突的设计**：以用户当场的话为准，并更新本文。

## 8. 术语

| 词 | 含义 |
|---|---|
| 门面 / facade | `business:api` 里的接口与模型，业务方唯一的依赖面 |
| 委托实现（原话：堆屎） | 阶段 2 的适配器：实现 `Agent` 接口，内部转发到 `LLMController` |
| 架空 | 阶段 3：把 `LLMController` 引用的协作者抽成服务，改为 `requireService<T>()` 取用 |
| 折叠 / 归约 | 把流事件累积成状态的过程；目标是只发生在实现侧一次 |
| iservice | 进程内服务注册表；reified 类型查找，不引 DI 框架 |
| 业务方 | `app` 模块里的消费代码；不包括组合根与适配器 |

## 9. 验证命令

```sh
# 门面模块自检（必须通过，且输出无 error）
# 注意：business:api 是纯 JVM 模块，没有 assembleDebug 变体
./gradlew :business:api:clean :business:api:assemble

# 契约的依赖边界（期望：只有 coroutines 与 stdlib）
./gradlew :business:api:dependencies --configuration compileClasspath

# 业务方是否还在直连实现（期望：只剩适配器与组合根）
grep -rn "LLMController\." app/src/main

# 「业务方干净」的总判据（阶段 5 的验收，期望：0）
grep -rln "import com.niki914.okia" app/src/main | wc -l

# 完整构建与单测编译
./gradlew :agent-runtime:testDebugUnitTest :app:compileDebugUnitTestKotlin :app:assembleDebug
```
