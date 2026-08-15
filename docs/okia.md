# Okia PRD — Okai 重写骨架（libs:okia）

## 设计哲学

设计哲学可以概括为：

运行时优先：核心解决模型调用、流式响应、工具执行、MCP 调用、取消、停止、资源释放和对话提交。
框架不替产品做决策：权限、审批、ACL、审计、历史迁移、未知工具处理、预算和业务重试，通过 hook 或下游逻辑完成。
最小公共抽象：不为假设中的未来场景增加 data class、策略对象或复杂状态机；只有无法用现有结构表达重要不变量时，才增加公共类型。如无必要，勿增实体
明确失败优于自动修复：非法消息、未知工具、旧历史与新工具不兼容，不由框架擅自伪造消息或替业务恢复。
按实际粒度设计：项目只需要 turn 级 rewind，不追求 Pi 那种 tool-call 级回退和分支编辑。
扩展点优于内置策略：核心提供稳定的 hook 时序、数据修改入口和生命周期保证，具体业务行为由使用者决定。
延迟设计未消费的 API：ToolCallHolder 的 write 方法等到真实使用场景出现后再定，而不是提前设计完整抽象。

因此，这个项目的边界是：

一个面向 Agent 运行时的基础库，类似 OkHttp；它负责可靠地把一次用户输入运行完成，并提供协议、工具、MCP、生命周期和 hook 基础设施，但不负责定义产品应该如何处理风险、权限、历史和异常业务。

## 0. 文档定位

本文档是 `libs:okia` 模块的完整设计意图。它记录：目标架构、全部已定决策（含原因与证据）、开放问题。会话重启后阅读本文档即可恢复完整上下文。

实现现状以源码为准；本文档与源码冲突时，以源码为准并回写本文档。

来源：白板架构图（用户手绘，含 5 张注意事项便签）+ 多轮架构讨论。白板原始导出见仓库根 `WB.txt`。

## 1. 命名规则

| 维度 | 取值 | 原因 |
|---|---|---|
| Gradle 模块 | `libs:okia` | `libs:okai` 已被现有实现占用，新模块须用新名才能共存 |
| 公开代码命名 | **全部用 `Okia`** | 与 `libs:okai` 的 `Okai` 命名区分，两模块长期共存（2026-08 决策，推翻原「Okai 转正」设计） |
| 包名 | `com.niki914.okia`（已定） | 避免与旧模块 `com.niki914.okai` 同包并存导致的 import 混淆 |

即：门面类 `Okia`、配置类 `OkiaConfig`、依赖装配 `OkiaDependencies`，全部用 Okia 前缀。写代码时禁止出现 `Okai` / `Okaii` 类名。

## 2. 背景与动机

- **Nexus 现状**：Nexus 把 kai 当回合执行引擎使用（`LLMController` 唯一持有实例），暴露的真实缺口：工具调用链管理/拦截能力缺失、错误分类不可移植、流式事件模型单薄、重试缺失、idle 检测语义错误、职责上溢（详见 `docs/kai-prd.md` §1）
- **重写而非修 kai**：kai 是历史实现，问题深入结构；okai 骨架（`libs/okai`）是重设计的第一版，但经 review 迭代后发现扩展点体系（拦截器链 + ForceStopHook + McpDiscoveryListener 三个平行接口）分散、协议 id 解析无用途、KMP 化有阻（JsonCodec/Clock 抽象）
- **本模块**：在 `libs:okai` 骨架的教训之上重写骨架，采纳白板架构的门面形态

## 3. 骨架约束

1. 全部编译通过：方法体 `TODO()`，无业务逻辑；**数据结构与功能设计必须固定**（骨架定型后不轻易改签名）
2. 顶层 `com.niki914.okia/` 下类数 ≤5，其余分包要详细
3. 所有 enum / sealed interface / data class 完整声明
4. 0 代码级注释（行内/方法内）；允许的方法注释只有类级 `/** */`，记录设计来源（kai / pi / codex / independent）
5. **新注释风格**：方法用空方法体 + `// 注释` + `return TODO()` 解释功能，类级注释缩减：

```kotlin
/**
 * 库门面：一次对话一个实例。send 启动回合；stop 取消回合。
 * Design source: independent facade design, surface from pi session-manager.
 */
interface Okia {
    // 提交用户输入，跑完整个回合（LLM ↔ 工具循环）后返回
    suspend fun send(text: String, onEvent: suspend (TurnEvent) -> Unit): Unit = TODO()
}
```

6. 0 历史包袱：不保留向后兼容，废弃路径直接删除
7. 从一开始考虑 JVM 单元测试便利（接口可 fake、依赖可注入）
8. 实现参考 Pi/Codex，绝不复制 kai

## 4. 目标架构

白板主流程（用户手绘图的文字重建 @WB.txt WB.txt 不是最终版本，以 PRD 为准，仅供参考）：

```
用户 "Hi, 帮我..."
  → send → Okia（门面）
       ├─ append: User(...) → Conversation
       ├─ 提供 Provider 类型 → ProtocolCompatMapper
       └─ Join until Conversation is ready（最新消息已 append）→ [Chat History Ready] → AgentLoop
  [Chat History Ready] → "Loops until last msg IS NOT Tool Request" → AgentLoop
  Conversation → Provide Data → AgentLoop
  AgentLoop → append: Ai | Tool Request | Tool Result → Conversation
  AgentLoop → Tooling → Tool Result → AgentLoop
  AgentLoop → ProtocolCompatMapper → "用 Map 后的数据发起请求" → HttpEngine
  HttpEngine → WHEN: failed → RetryStrategy [503, 429, ...]
```

各节点职责：

| 节点 | 职责 |
|---|---|
| `Okia` | 门面。一次对话的载体（不是 OkHttpClient 式可复用）。持有单活跃回合 |
| `Conversation` | 数据结构维护者。内部 Mutex 竞争控制；Rewind 能力 |
| `[Chat History Ready]` | 屏障：send 先 append User，等待 Conversation 就绪（最新消息可读）再启动 AgentLoop |
| `AgentLoop` | 回合驱动。LLM ↔ 工具循环直到"最后一条消息不是工具请求" |
| `Tooling` | 工具执行。永不抛异常，总是产出工具结果 |
| `ProtocolCompatMapper` | 协议无关 dataclass → Provider 序列化的边界。上层不感知协议 |
| `HttpEngine` | 传输。失败时进入 RetryStrategy |
| `Hooks` | 面向下游开发者的统一扩展面（见 §5.9） |

5 张便签（白板原文要点）：
- W1 UI 数据结构 / 实例语义
- W2 Hooks 设计意图
- W3 Conversation 类设计
- W4 suspend / onInterrupt / 不抛异常
- W5 Provider 生命周期

（全部逐句解析见 §5 各节；完整原文在 `WB.txt`）

## 5. 核心设计决策

每条格式：**决策** → **原因/背景** → **证据/先例**。

### 5.1 Okia = 单对话载体（W1）

**决策**：一个 `Okia` 实例承载一次对话、至多一个活跃回合。多次对话 = 多个实例。实例间切换 = 新建实例，不做实例内会话切换。

**原因**：对话状态（Conversation）内聚在实例内；并发控制（Mutex）只作用于本实例。

**先例**：pi session-manager（fork 产生新会话文件）；kai 的 `OKai` 单会话实现。

### 5.2 并发：Mutex + 抛异常（W1）

**决策**：**`ConcurrencyMode` 枚举删除**。实例内部用 Mutex 串行化；并发调用（活跃回合存在时再次 send）直接抛异常。

**原因**：白板 W1 "Okia 务必用 Mutex 做并发控制，目前希望在并发的时候抛出异常"。Nexus 实证：`AgentRuntimeService` 是"先 cancel 再提交"（stop-then-send），库内 Replace 语义无需求——Replace 可由 `stop() + send()` 组合表达。Queue 亦无需求。三值枚举收敛为单一行为。

### 5.3 Conversation 类（W3）

**决策**：数据结构由**内部类 `RealConversation`**（conversation/ 包，公开面之外）维护：条目树（id / parentId / timestamp）+ 可变的 leafId 当前位置，内部 Mutex 竞争控制（KMP 下唯一同步方案 = `kotlinx.coroutines.sync.Mutex`）。`rewind(entryId)` 原地移动 leafId 指针，被跳过的尾部保留在树中。**rewind 校验 entryId 存在（不存在抛 IllegalArgumentException），但位置语义不校验（放开）**：回退粒度由下游自行约束，停在未配对工具调用等位置的后果由下游负责——库不替下游决定什么位置合法（篡改历史的场景是下游的合法用途）。**改第一条消息 = 新建实例（§5.1），库不提供回退到 root 的 API。**命名参考 OkHttp `Real*` 惯例：公开短名，实现类带 Real 前缀。

**原因**：W3 "单独类维护数据结构 + 内部竞争控制 + Rewind"；rewind 后历史投影 = leaf 到 root 线性投影。内部化的原因：公开面只需不可变快照，可变树是库内细节，暴露它会导致下游绕过门面直接修改（CR #1 裁决）。

**先例**：pi `buildSessionPath(entries, leafId)`（leafId 显式 + fallback 到最后一条）、SessionHeader version；OkHttp 公开接口短名 + `RealCall` / `RealInterceptorChain` 实现命名。

**持久化**：`SessionSnapshot(id, leafId, version, entries)` 由 codec 接口持久化（存储位置 host 决定）。**leafId 必须持久化**（rewind 位置在重载后保持；null = 恢复为最后一条）。`entries` 为消息级 `ConversationEntry`（树节点 + 持久化行格式，非门面类型，下游仅在持久化时接触）。

**持久化入口（CR 第三轮落地）**：门面 `Okia.export(): SessionSnapshot` 导出当前完整树 + id + leafId；恢复 = 重新 `open(restore = snapshot)`（协议由 host 重新提供，§5.7 不变）。公开 `Conversation` 快照补 `leafId`（rewind 当前位置，UI 可读）；`MessageEntry` 补 `timestamp`（历史渲染）。

### 5.4 UI 数据模型：StateFlow + SharedFlow（W1）

**决策**：库提供 `StateFlow<Conversation>` 作为持久性数据源（UI 观察它渲染全部内容），`SharedFlow` 提供失败等一次性事件。参考 MVI。**`Conversation` 是公开不可变快照 dataclass，不是可变树**：

```kotlin
data class Conversation(
    val id: String,
    val leafId: String?,               // rewind 当前位置（null = 最后一条）
    val history: List<MessageEntry>,   // 已提交的完整消息（leaf 投影，平列表）
    val live: AssistantMessage?        // 正在流式、尚未成条的助手消息；空闲 null
)

data class MessageEntry(
    val id: String,                    // rewind(entryId) 的目标，直接可取
    val timestamp: Long,               // 历史渲染时间戳（由会话树唯一承载）
    val message: Message
)
```

**原因**：下游开发者极可能用 Compose；状态即数据流比事件累计更 Kotlin 原生。不可变快照使 StateFlow 每次发射都是新值（不依赖可变对象 emit 语义）；门面条目（`MessageEntry`）把 id 与消息绑定，下游回退目标直接从快照拿，无需接触树结构。turn 边界由下游按 `Message.User` 自行封装（库不提供 turn 分组——替下游做决定）。

**更新粒度（消息级）**：状态流按**消息**更新，不按回合。loop 的消息产出经 `LoopRequest.onCommit` 逐条/逐批即时提交（facade 注入，`RealConversation` 同一把 Mutex 下原子追加），facade 用 `updateState { copy(...) }` 重投影。`TurnResult` 不再携带消息（已随 onCommit 提交），收敛为 sealed（`Completed` / `Failed` / `Aborted` / `IdleTimeout`）。**`send` 返回 `TurnResult`**：终态由 sealed 承载、字段必带，失败不抛异常；onEvent / events 只承担流式中间过程。调用方不再自建"最后一条终态事件"累计。

**流式语义**：
- **Text**：有一点变化就反馈给 UI（`live` 逐 delta 更新快照）
- **Tool**：arguments 组装完成（`ToolCallReady`）之前**不进入 UI 状态**（不占位）；组装完成后随助手消息成条进入 `history`，工具块状态 = `Start → Running → 终态（ToolCallOutcome）`（Running 态 = 已提交工具调用尚无对应 ToolResult，UI 从 history 推导）

**事件协议**：`TurnEvent` 保留（§8.1 候选 A 已裁决）：宿主 IPC（RenderFrame 流式回调）走事件形态；StateFlow 是已提交历史的投影。`live` 是快照中唯一的中间态。工具调用分两个生命周期，事件前缀区分：`ToolCall*` = 模型产出调用意图（`ToolCallStarted` / `ToolCallDelta` / `ToolCallReady`，Ready 为参数组装完成、待执行）；`Tool*` = 工具执行状态（`ToolRunning` / `ToolSucceeded` / `ToolFailed`，Succeeded/Failed 携带 `ToolCallOutcome`）。工具执行状态同时出现在两条轨上：事件流携带当刻产生的 outcome，StateFlow 从 history 推导；消费方按用途取其一。

### 5.5 Tooling 契约（W4）

**决策**：
1. 从门面入口开始，内部方法全部 `suspend`（支持打断）
2. **Tooling 永不抛异常，总是产出工具结果**。自定义工具强制实现 `onInterrupt`（返回工具结果），中断判定 = executor 内部状态
3. 中断的资源清理是下游职责，库只提供回调时机（`beforeStop`）
4. **`ToolCallContext` 不携带对话上下文与重试计数**：ToolExecutor 知道完整对话历史是越界；幂等性由 call id 承载（重试时 id 不变，工具自行记录已处理的 id）。需要会话归属信息的工具由 host 在注册时自行注入

**先例**：okai 骨架的 `ToolExecutor.interruptedOutcome`；PRD 4.4 中断收尾分工（未派发 → loop 标记；已派发 → executor 判定）。

### 5.6 ToolCallOutcome（5 态）

**决策**：

```kotlin
sealed interface ToolCallOutcome {
    data class Success(val content: String) : ToolCallOutcome
    data class Failure(val message: String, val content: String? = null) : ToolCallOutcome
    data class Intercepted(val reason: String, val content: String? = null, val isError: Boolean = false) : ToolCallOutcome  // hook 拦截结果
    data class Interrupted(val content: String? = null) : ToolCallOutcome
    data class Unknown(val message: String, val content: String? = null) : ToolCallOutcome
}
```

**`Blocked` 删除的原因**：Blocked 是"审批拒绝"的具体语义，应由下游 hook 泛化（拒绝 = `Intercepted` 或 `Failure`）；okai 骨架的 Blocked 值被裁掉。
**`Intercepted` 新增的原因**：hook 拦截 ≠ 工具失败，UI 要区分；hook 不只给失败结果（可能给成功模拟、缓存命中、拦截）。机制语义，下游自由泛化。**`Intercepted.content` 的原因**：缓存命中 / 成功模拟必须把结果负载回喂模型（`encodeToolResult` 产出 ToolResult 消息），与 `Failure`/`Unknown` 的 `(message, content)` 同构（CR 裁决）。**`Intercepted.isError` 的原因（CR 第三轮落地）**：Provider 编码的 isError 由 outcome 派生，但 Intercepted 语义上覆盖阻断（审批拒绝 = 错误）与结果替换（缓存命中 / 成功模拟 = 成功），派生函数无输入可分；补 `isError: Boolean = false` 字段，由写入方（hook）传递——审批拒绝传 true，缓存命中 / 模拟传 false。其余 4 态均可唯一派生。

工具块 UI 终态 = 这 5 态（Start/Running 是过程态，见 §5.4）。`ToolResult` 消息内嵌同一 outcome（无状态映射，中断语义在持久化恢复后可读）。

### 5.7 Provider 生命周期（W5）

**决策**：
1. 实例化时协议定死：`Okia.open(protocol: P, restore = null, builder)`（协议实例）+ `Okia.open(restore = null, builder)`（默认 M0 DeepSeek，库内部构造）+ `Okia.open(dependencies, restore = null, builder)`（测试/高级装配）；`restore` 为可选 `SessionSnapshot` 恢复快照（持久化入口，§5.3，null = 新对话）
2. 协议作用域 == Okia 实例生命周期；实例由调用方构造（`withCodec` / 自定义状态在 open 前就绪），open 后归 Okia 持有
3. **持久化与恢复无矛盾**：恢复时重新 `open(protocol)` 提供 Provider；协议 id 不进会话数据
4. **`ProtocolRegistry` 删除**：id 解析无用途（host 自己知道自己用什么协议，Nexus 的 `LlmApiType` 存 Room、恢复时 `openSession` 重新 open）
5. **`KClass`/reified 重载删除**：KMP 目标（jvm + android + ios）无通用反射，类型令牌无法实例化任意协议接口；保留 `open<P>(protocolClass)` 是误导性 API（对非内置协议必然无法构造）。下游想封装自己的便捷入口（如内部持有协议实例的 `openSession`）由 host 自行实现

**先例**：kai `Kai.open<P>` 泛型绑定；Nexus `LLMController.obtainSession`（apiType 变化 → close + 重建）。

### 5.8 分层与序列化边界（W5）

**决策**：上层（loop / Conversation / Hooks / UI）协议无关，只用自定 dataclass（`Message` / `ContentBlock` / `RequestSnapshot`）；数据到 `ProtocolCompatMapper` 及以下（`ChatProtocol.buildRequest` / `encodeToolResult`）才按 Provider 序列化。host 用抽象 dataclass 实例化、不碰网络 raw data → 切换协议无影响。

保留：`ChatProtocol`（id / withCodec / useApiKey / buildRequest / parseStream / encodeToolResult / compat）、`Compat` 矩阵（maxTokensField / thinkingFormat / retryableStatusCodes 等，M0 仅 DeepSeekCompat）、`ProtocolEvent`（协议无关中间表示，与库级事件两层映射）。**`Compat` 挂在 `ProtocolCompatMapper` 上**（loop 已持有 mapper，经 `encodeToolResult` 已跨越协议边界，compat 是同一边上的另一类事实；loop 在历史拼装与重试时查询）。

**`buildRequest` 无独立 pendingUserInput**：不变量为 **history 永远包含当前输入**（send 先提交 User 消息，再启动 loop）。`pendingUserInput` 参数与 `SerializationHolder.pendingUserInput` 字段已删除（CR #3 裁决）。

**依赖图闭合（CR 第三轮落地）**：
- **mapper 是 ChatProtocol 的适配壳**：`ProtocolCompatMapper.from(protocol)` 工厂声明两者连接——`open(protocol)` 内部经此构造，loop 只接触 mapper、不接触 ChatProtocol。
- **传输入口进 LoopRequest**：`LoopRequest.httpEngine`（回合唯一传输入口，AgentLoop 必须经它发请求）+ `LoopRequest.retryPolicy`（传输层重试：`Compat.retryableStatusCodes` + 指数退避）；回合层重试仍在 `LoopOptions.turnRetryPolicy`。白板 RetryStrategy 节点由此闭合，自定义 AgentLoop 无法绕过注入的 engine。
- **idle 检测观察原始 SseLine 流**：`idleTimeoutSeconds` 计时器挂在原始流（parseStream 之前），任何到达帧（含 keep-alive 的 null data）重置——keep-alive 活跃度不随 parseStream 丢弃而丢失。kai 旧实现按事件间隔计时导致长思考误杀（PRD §1.5），此处封死。

**hook 与会话树的不变量**：**树（conversation）= 事实**。hook 的 mutation 永远只作用于"本次操作的一次性载体"（holder），发完即弃，**不写回会话树**。因此"UI 显示原文 vs 模型收到改写版"不是不一致性，而是分层预期——例：`beforeSerialization` 数据脱敏时，UI 显示未脱敏原文（自己的界面），模型收到脱敏版（对外边界）。若下游要修正历史本身，正路是 rewind 后重新生成（新分支），不是 hook 隐式改树（链式 hook 会互相踩、历史不可信）。

### 5.9 Hooks 体系（W2，核心）

#### 5.9.1 定位

面向**下游开发者**的**统一扩展面**，用于自定义 Okia 在他们手中的表现。参考 Pi/Codex 钩子设计，**采用一部分，不全部照抄**；API 驱动（Kotlin 注册），**不是** Pi 式配置文件驱动（Pi 从磁盘加载 TS 模块）。

#### 5.9.2 参考清单（调研结果）

**Pi extensions**（hooks 的现代形态，Pi 已把 hooks 改名为 extensions）：33 个事件时机。关键族：`tool_call`（可 block + 改参数）、`tool_result`（可改结果）、`input`（可 transform）、`context`（可改 messages）、`before_provider_request`（可替换 payload）、`before_provider_headers`、`session_before_*`（可取消）等。分发机制：`handlers.get(eventType)` 按注册顺序执行，后注册结果覆盖，`cancel` 立即短路。证据：`/tmp/pi/packages/coding-agent/src/core/extensions/types.ts`、`runner.ts`。

**Codex hooks**：11 个 `HookEventName`（PreToolUse / PermissionRequest / PostToolUse / PreCompact / PostCompact / SessionStart / SessionEnd / UserPromptSubmit / SubagentStart / SubagentStop / Stop）+ 3 个维度：HandlerType（Command/Prompt/Agent）、ExecutionMode（Sync/Async）、Scope（Thread/Turn）；输出条目含 Stop（可中止）。证据：`/tmp/codex/codex-rs/protocol/src/protocol.rs:1499`、`hooks/src/`。

**不照抄的部分**：Codex 的 `Command` handler（跑外部命令——Android 库无 shell 钩子）；Pi 的 `model_select` / `user_bash` / `project_trust` / `resources_discover`（UI 层职责）。

#### 5.9.3 形态：多方法接口 + 链式

**决策**：
- 一个 `Hooks` 接口，所有时机都是方法，全部可重写，默认空实现
- **链式**：注册多个实现，按注册顺序执行；一个 event 可以走多个 hook（前一个的修改对后一个可见）
- 命名：**`beforeXXX` / `afterXXX` 成对**（Xposed 风格；`onXXX` 语义不清弃用）。"可以不用但不能没有"——每时机成对声明，默认空实现
- 全部 `suspend`，**默认阻塞**（调用方 await）；下游要异步自己开子协程——框架不做 fire-and-forget 机制（保持纯粹）
- 全部返回 `Unit`；可改数据走 **mutation**（holder + 签名 write 机制，见下）

**注册位置**：注册给 `OkiaConfig`（builder DSL：`hooks += ...`），不注册给实例。有状态 hook 的状态隔离是下游职责（状态外置）；hooks 列表可用 `update {}` 调整。先例：kai 的 `hooks {}` DSL 在 config；Pi/Codex 的 hooks 均在配置层。

#### 5.9.4 时机清单

| 时机 | before 形参 | after 形参 | 时序位置 |
|---|---|---|---|
| `Input` | `input: InputHolder` | `input: InputHolder` | 用户输入进入后 |
| `Serialization` | `request: SerializationHolder` | `request: SerializationHolder, httpRequest: HttpRequest` | 消息序列 → buildRequest 前后（约等于序列化前后） |
| `Request` | `request: HttpRequestHolder` | `request: HttpRequest` | 模型流式请求（`HttpEngine.stream`）发送前后 |
| `ToolCall` | `call: ToolCallHolder` | `call: ToolCallHolder, result: ToolResultHolder` | 工具执行前后 |
| `Stop` | `calls: List<ToolCall>` | `calls: List<ToolCall>` | 停止流程开始前 / 完成后 |

用途映射：
- 审批/拦截/参数改写 → `beforeToolCall`（阻断机制见开放问题 6.1）
- 审计/埋点 → `afterToolCall`、`afterStop`（对称保留，埋点统计有用）
- 数据脱敏 → `beforeSerialization`（主战场，协议无关层）＋ `beforeRequest`（http 层兜底）
- kill-then-stop → `beforeStop`（§5.11）

**覆盖边界**：`beforeRequest` / `afterRequest` 只覆盖模型流式请求（`AgentLoop` 经 `HttpEngine.stream()` 的路径）；`HttpEngine.unary()` 是 MCP 等其他网络请求，不触发任何 hook。`afterRequest` 时机 = 请求已发出后，只看到实际发出的 `HttpRequest`（只读），不接触 response——body 流归 loop 独占，类型上保证 hook 无法消费。

**删除**：`onFork` / `onRewind`（fork 已删除；rewind 是 Conversation 内部同步数据结构操作，无外部动作可钩）；`InterceptorChain`（§5.13）。

#### 5.9.5 mutation holder

**决策**：所有 before 的可改数据走 holder 对象：字段只读暴露，写入走 `write` 方法并**记录签名字段**（可追溯最后写入者，审计友好）。**骨架期 holder 只声明字段，write 方法留空**（没有消费者，不设计 API）。holder 归 `hooks/` 子包。

**形态选择的背景**：mutation（Pi 做法：原地改 `event.input`，后续 handler 可见）vs 返回值传递（OkHttp interceptor 式）。用户从使用角度选 mutation（只有要改时才调用 write，比"每个 hook 想返回什么"负担小）；签名 write 解决工程上的可追溯性（"最后是谁改的"）。

### 5.10 异步 Terminal 注入（host 侧拼接，不走 hook）

**决策（CR 第三轮修正）**：`beforeInput` 不再承担"异步任务完成通知注入"。异步注入由 **host 自行拼装进 send 文本**：业务方把后台任务完成通知入队，下一次用户输入时在调用 `send` 前拼接为完整 query 文本再提交。通知是瞬态业务状态，进会话树即污染历史；host 每轮自行组装可保持树 = 对话事实。

**Nexus 实证**（`agent-runtime/.../LLMController.kt:195-203`）：terminal 工具 `background=true + notify_on_complete=true` → `TerminalSessionPool.startAsync` 后台执行 → 完成时通知入队 → 下一次用户输入时 `drainPendingNotifications()` 把 `[IMPORTANT: Background process ...]` 拼接进 query 前缀再 send——**保留现有 host 侧文本拼接，不下沉**。

**beforeInput / afterInput 保留，语义不变**：hook 的 mutation 只影响本次请求载体，不写回会话树（§5.8 不变式）。输入改写若有真实消费场景（如输入规范化），由业务方在 hook 中自行定义；骨架期仅声明槽位，无内置用例。

### 5.11 kill-then-stop → beforeStop

**决策**：`beforeStop` 在取消回合 job **之前**调用（kill 步骤），`calls` 参数限定本回合已派发的工具调用（共享全局资源池的 host 不会误杀其他会话的工具）。每个被取消回合至多调用一次；hook 抛异常被捕获，不中止停止流程。

**原因（死锁论证）**：协作式取消不影响阻塞工具调用（子进程 readLine、blocking socket）→ 若在 loop cleanup 中调用钩子则永不执行 → `stop()` join 永远挂住。必须先 kill 再 cancel。

**Nexus 实证**（`LLMController.kt:266-278`）：`PyRuntime.kill()` → `TerminalSessionPool.closeAll()` → `kai.stop()`——注释明言"不先杀，Kai 的 stop 会 join 等待工具协程直到命令自然结束"。

### 5.12 读屏前置：伪需求，不是 Hooks 用例

Nexus 的"屏幕操作前先读屏"已通过**版本号机制**强制实现（操作前校验屏幕快照版本）。它不是 Hooks 的用例，不纳入设计。曾误以为它是 `beforeToolCall` 改参数的驱动场景——不成立。

### 5.13 删除项汇总（相对 okai 骨架）

| 删除 | 原因 |
|---|---|
| `ConcurrencyMode` | §5.2 |
| `ProtocolRegistry` | §5.7 |
| `JsonCodec` | KMP 标准替代：kotlinx.serialization（协议无关 dataclass 直接 `@Serializable`） |
| `Clock` | KMP 标准替代：kotlin.time.Clock（或保留接口，实现换 kotlin.time.Clock，待定） |
| `ContextPolicy` / `ContextBudget` | PRD 4.6 明确 compaction 口子 M0 不实现、host 实现；骨架放接口 = 空占位，推迟 |
| `ToolCallInterceptor` + `ToolCallChain` | 并入 Hooks（§5.9）。拦截器链的顺序/短路/改参能力由"链式 hooks + mutation + 拦截结果"表达 |
| `ForceStopHook` | 并入 `beforeStop` |
| `McpDiscoveryListener` | 删除（无 hook 替代，观察走 `refreshMcpTools` / `getMcpDiscoverySnapshot`） |
| `ToolCallOutcome.Blocked` | §5.6（新增 `Intercepted`） |
| `Okia.open(protocolClass)` / reified 重载 | §5.7：KMP 无通用反射，类型令牌无法实例化任意协议；改为 `open(protocol: P)` + 默认 `open(builder)` |
| `TurnResult.messages` | §5.4：消息已随 `LoopRequest.onCommit` 消息级提交，TurnResult 收敛为 sealed 结局 |
| `Conversation` 公开可变树 | §5.3/§5.4：内部化为 `RealConversation`，公开面为不可变 `Conversation` 快照 + `MessageEntry` 门面 |
| turn 分组投影 | §5.4：turn 边界由下游按 `Message.User` 自行封装，库不替下游决定回退粒度 |
| `pendingUserInput` | §5.8：不变量为 history 包含当前输入 |
| `ToolCallContext.conversation` | §5.5：ToolExecutor 知道完整对话历史是越界 |
| `afterInput.handled` | §5.9：无可写入口（悬空）+ 与 `InputHolder.lastWriter` 冗余 + 无消费者 |

**保留**：`ChatProtocol` / `Compat` / `ProtocolEvent` / `RequestSnapshot`（协议层）、`Message` / `ContentBlock` / `Usage` / `StopReason`、`Session` 树 + `SessionCodec` + leafId 持久化（§5.3）、`ToolExecutor` / `ToolRegistry` / `ToolCallContext` / `ToolDescriptor`、`McpClient` / `McpServer` / `McpExecutor` / `McpDiscoverySnapshot`（Nexus 重度使用：fingerprint 刷新 + PromptComposer 渲染）、`HttpEngine` + transport 数据类（KMP actual 点）、`LLMError` / `RetryPolicy`（Nexus 手工分类要下沉）。

**资源所有权**（close 规则）：装配时宿主传入的资源（`httpEngine` / `toolRegistry` / `agentLoop` / `mcpClient` / 协议实例）**宿主所有**，`close()` 不关闭；config 未提供的默认资源（默认空 `ToolRegistry`、自建 `HttpEngine`）实例所有，`close()` 释放自建部分。

### 5.14 KMP 目标

- 库定位：**KMP Agent 基建**（jvm + android + ios）
- 现状：okai 骨架源码零 Android 类型引用，纯 Kotlin 可编译，仅 `com.android.library` 插件——换 KMP 插件无源码阻碍
- 同步方案：仅 `kotlinx.coroutines.sync.Mutex`（W3"整个库只能用 Kotlin 的同步方案"）
- JSON：kotlinx.serialization；时钟：kotlin.time.Clock；HTTP：`HttpEngine` 接口保留（JVM/Android OkHttp actual，iOS Ktor/NSURLSession actual）
- UI 流：StateFlow/SharedFlow（coroutines 多平台）

### 5.15 事件协议（开放问题）

okai 骨架有 `TurnEvent` 11 种 + `FinishReason` + `StopCause`（PRD 4.2）。宿主 IPC 实证需要流式回调（`AgentRuntimeService.executeTurn` 把 `RenderFrame` 经 Binder 发给 Breeno/XiaoAi——事件流形态，宿主进程没有 UI 观察 StateFlow）。

倾向：**库内保留事件协议（事实）+ `StateFlow<Conversation>` 投影（UI）**。是否在骨架期声明 `TurnEvent` 待定（开放问题 6.2）。

## 6. 开放问题（未定决策）

6.1–6.6 已在 §8.1 裁决，不再列为开放项。当前仅剩：

| # | 问题 | 背景 | 候选 |
|---|---|---|---|
| 6.7 | 消息注入 API | 下游可能想篡改历史（rewind 后手动补 ToolResult）；rewind 已放开，但无主动注入消息的入口 | 未来如需，加显式 `append`/`inject` 方法（rewind 后手动补消息），不靠库自动修复；当前无消费者，不实现 |

## 7. 参考资料

- 白板原始导出：`WB.txt`（仓库根）
- 现设计 PRD：`docs/kai-prd.md`（§4.1-4.7 为能力依据；okai 骨架已实现其接口形态）
- Pi：`/tmp/pi/packages/coding-agent/src/core/extensions/types.ts`（33 时机）、`runner.ts`（分发机制）、`session-manager.ts`（fork/leafId）
- Codex：`/tmp/codex/codex-rs/protocol/src/protocol.rs:1499`（HookEventName）、`hooks/src/`（declarations/registry）、`core/src/session/turn.rs`（hooks 调用点）
- Nexus 实证：`agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/LLMController.kt`（kill-then-stop :266-278、异步注入 :195-203、协议切换 :280-288）、`TerminalSessionPool.kt`（异步任务通知队列）
- okai 骨架现状：`libs/okai/src/main/java/com/niki914/okai/`（36 文件，重写的对照基线）

## 8. 骨架落地记录（2026-08-09）

本节记录 `libs:okia` 骨架落地时对本文档的解析与未定项的裁决。源码为准；后续实现偏离此处时回写本节。

### 8.1 开放问题裁决

| # | 问题 | 裁决 | 说明 |
|---|---|---|---|
| 6.1 | beforeToolCall 阻断机制 | A：全 Unit + holder 预留 outcome 字段 | `ToolCallHolder.outcome` + `writeOutcome` 已声明（骨架期 write 留空） |
| 6.2 | TurnEvent 保留与否 | A：保留事件 + StateFlow 投影 | `Okia.events: SharedFlow<TurnEvent>` + `Okia.conversation: StateFlow<Conversation>` 并存；send 保留 onEvent 回调（宿主 IPC 流式回调） |
| 6.4 | hooks 列表可变性 | A：只读 List + builder 累积 | `OkiaConfig.hooks: List<Hooks>`，Builder 内 `hooks += ...` |
| 6.5 | 包名 | `com.niki914.okia` | 避免与旧模块同包 import 混淆 |
| 6.6 | Clock 去留 | 删除 | kotlin.time.Clock 为 KMP 标准替代；骨架无 Clock 类型 |
| 6.3 | 重试归属 | A：RetryPolicy 在 config | 未另行裁决，保持候选 A 推荐方向 |

### 8.2 源码对本文档的增补与修正

1. **StopCause 删除 Replace**：`ConcurrencyMode` 删除后库内无 Replace 语义，`StopCause` 收敛为 `UserStop / External` 两值（`event/TurnEvent.kt`）。
2. **Okia 门面删除 getHistory / replaceHistory / resetConversation**：历史经 `conversation` 流投影可读；新对话 = 新建实例（§5.1），原地重置无需求。保留 refreshMcpTools / getMcpDiscoverySnapshot。
3. **Conversation 无 clear()**：同上，新对话走新实例。Conversation 只有 append / fork / rewind 与投影。
4. **afterStop 形参定为 `calls: List<ToolCall>`**：§5.9.4 表格 Stop 行 after 形参为「—」，但「每时机成对声明」与埋点用途要求 afterStop 存在，取与 beforeStop 相同形参。
5. **withCodec 参数类型 = `kotlinx.serialization.json.Json`**：JsonCodec 删除后，协议数据 / SSE payload / schema 均为 JSON，直接注入 `Json`（不再用宽泛的 `StringFormat`，避免非 JSON 格式被注入）。
6. **协议无关 dataclass 标注 `@Serializable`**：Message / ContentBlock / ToolCallOutcome / AssistantMessage / Usage / StopReason / ConversationEntry / SessionSnapshot 直接可序列化（§5.13 JsonCodec 删除的依据落地）。
7. **ToolCallContext.session → conversation（后已删除）**：命名曾对齐 §5.3；本轮 CR 裁决删除字段——ToolExecutor 知道完整对话历史是越界（见 8.2-11）。
8. **holder 直接置于 `hooks/` 子包**：§5.9.5「holder 归 hooks/ 子包」按「hooks 包的子包」即 `com.niki914.okia.hooks` 落地。
9. **OkiaDependencies 移入顶层包**：Clock / ForceStopHook 删除后 runtime 包无剩余内容，依赖装配并入顶层（顶层共 4 个类型，满足 ≤5 约束）。
10. **M0 构建**：AGP 9 内置 Kotlin + `org.jetbrains.kotlin.plugin.serialization` 2.2.0 + kotlinx-serialization-json 1.7.3；`./gradlew :libs:okia:compileDebugKotlin` 通过。

### 8.4 第二轮 CR 落地（2026-08-10）

PR #109 review（head `091e912`）裁决的签名级修改，与 §5 各节同步：

1. **公开快照 + 内部树**：`Conversation` 变为公开不可变快照（`history: List<MessageEntry>` + `live`）；树实现内部化为 `RealConversation`（conversation/RealConversation.kt）。`MessageEntry(id, message)` 为门面条目（rewind 目标直接可取）；`ConversationEntry` 退为树节点 + 持久化行格式。命名参考 OkHttp `Real*` 惯例。
2. **rewind 放开**：不校验、不抛异常——回退粒度由下游自行约束（库不替下游决定什么位置合法）；非法回退后果写文档、由下游负责；消息注入 API 记为开放问题 6.7。
3. **消息级状态流**：`LoopRequest.onCommit`（facade 注入，`RealConversation` 同一 Mutex 下原子追加）承载消息产出；`TurnResult` 删 messages，收敛为 `(reason, cause)`；快照更新 = facade `updateState { copy(...) }`（每条消息一次）。
4. **open 系列**：删 `KClass`/reified 重载（KMP 无通用反射，类型令牌无法实例化任意协议）；改为 `open(protocol: P, builder)` + `open(builder)`（默认 M0）+ `open(dependencies, builder)`。
5. **删除 pendingUserInput**：`buildRequest(snapshot, history)` 与 `SerializationHolder` 同步删除；不变量 = history 永远包含当前输入（send 先提交 User 再启动 loop）。
6. **`Compat` 挂在 `ProtocolCompatMapper`**：loop 经 mapper 查兼容事实（历史拼装 / 重试），零新增 plumbing。
7. **`ToolCallContext.conversation` / `attempt` 删除**：ToolExecutor 知道完整对话历史是越界；幂等性由 call id 承载（重试时 id 不变），重试计数是框架状态外泄且语义未定义（工具失败路径是结果回喂模型，无工具重试）。需要会话归属的工具由 host 注册时自行注入。
8. **`afterInput(input)` 删 handled**：无可写入口（悬空）+ 与 `InputHolder.lastWriter` 冗余 + 无消费者；接管需求出现时再加 `writeHandled()`。
9. **`OkiaConfig.toolRegistry`**：builder 可注入生产级工具注册表（null 时门面自建空 registry，实例所有）；demo 显式传入。
10. **资源所有权**（fork/close）：宿主传入资源（httpEngine / toolRegistry / agentLoop / mcpClient / 协议实例）宿主所有、`close()` 不关；默认资源实例所有；fork 共享宿主资源与 config 快照、各自独立 `RealConversation`。
11. **`Intercepted` 加 `content: String? = null`**：缓存命中 / 成功模拟需把结果负载回喂模型，与 `Failure`/`Unknown` 同构。
12. **`McpServerDiscoverySnapshot.tools`**：`List<McpDiscoveredTool>` 补全文档承诺（host 组合 prompt / 持久化发现结果）。
13. **hook 与会话树不变量写文档**：树 = 事实；hook mutation 一次性、不写回树（§5.8，含脱敏分层例子）。hook 异常策略：默认该步骤失败（模型段 → 回合失败；工具段 → `Failure` outcome），`beforeStop` 保持捕获特例（§5.11）。

### 8.5 骨架文件清单（39 文件）

```
com.niki914.okia/
├── Okia.kt / OkiaConfig.kt / OkiaDependencies.kt / TurnOptions.kt   （顶层 4 类型）
├── conversation/  Conversation.kt（快照 + MessageEntry + ConversationEntry）、RealConversation.kt（内部树）、SessionCodec.kt（SessionSnapshot）
├── loop/          AgentLoop.kt（AgentLoop / TurnResult / LoopRequest）、LoopOptions.kt
├── tooling/       ToolExecutor.kt、ToolRegistry.kt（+ToolDescriptor/ToolKind）、ToolCallContext.kt
├── message/       Message.kt、ContentBlock.kt、ToolCallOutcome.kt（5 态）、Usage.kt
├── protocol/      ChatProtocol.kt、Compat.kt（+DeepSeekCompat）、ProtocolEvent.kt、ProtocolCompatMapper.kt、RequestSnapshot.kt
├── hooks/         Hooks.kt（10 时机）、InputHolder / SerializationHolder / HttpRequestHolder / ToolCallHolder / ToolResultHolder
├── mcp/           McpClient.kt、McpServer.kt、McpExecutor.kt、McpDiscoverySnapshot.kt
├── transport/     HttpEngine.kt、HttpRequest.kt、HttpResponse.kt、StreamResponse.kt、SseLine.kt
├── error/         LLMError.kt、RetryPolicy.kt
└── event/         TurnEvent.kt（终态事件；StopCause）
```

### 8.6 第三轮 CR 落地（2026-08-11）

1. **send 返回 TurnResult**：终态由返回值承载（Stop / Length / Error / Aborted / IdleTimeout / RetryExhausted），失败不抛异常；事件流只承担流式中间过程（§5.4）。调用方不再自建终态事件累计。
2. **持久化入口**：门面 `export(): SessionSnapshot` + `open(restore = ...)`（§5.3 / §5.7）；`Conversation` 补 `leafId`、`MessageEntry` 补 `timestamp`。
3. **依赖图闭合**（§5.8）：`ProtocolCompatMapper.from(protocol)` 工厂；`LoopRequest` 加 `httpEngine` / `retryPolicy`（传输层重试）；idle 检测观察原始 SseLine 流（parseStream 之前，keep-alive 帧重置计时器）。
4. **Intercepted 补 `isError`**：审批拒绝 = true，缓存命中 / 成功模拟 = false；Provider 的 isError 派生由此闭合（§5.6）。
5. **异步注入移出 beforeInput**：host 自行拼装进 send 文本（§5.10）；beforeInput / afterInput 保留，hook 语义保持"只影响单次、不写回树"（§5.8）。
6. **快照防御性复制**：RealConversation 各 getter 与门面快照构造返回复制（构造即复制），fork 共享节点不可经公开面改写（§5.3 / §5.4）。

### 8.7 第四轮 CR 落地（2026-08-13）

签名与规则收敛，全部为数据结构 / 文档层，无实现逻辑：

1. **TurnResult 改 sealed，删除 FinishReason**：回合结局由 `sealed interface TurnResult` 表达——`Completed(stopReason)`（只可能是 Stop / Length）、`Failed(error)`（Error / RetryExhausted 经 `LLMErrorCode.RetryExhausted`）、`Aborted(cause)`、`IdleTimeout`。字段必带，消除 `reason=Error 但 error=null` 等含糊态（覆盖 §8.4 #3 与 §8.6 #1 的 `(reason, cause)` 旧形态）。事件层同步：`TurnCompleted(message)`（message 自带 stopReason）、`TurnFailed(message, error)`、`TurnAborted(message, cause)`、`TurnIdleTimeout(message)`。
2. **ProtocolEvent.Completed 补 stopReason**：`stopReason: StopReason?`，协议层映射后的消息级结束原因；Provider 不支持 finish reason 时为 null（loop 默认按 Stop）。`Compat.supportsFinishReason` 由此闭合。
3. **HttpEngine.stream 改 suspend**：`suspend fun stream(request): StreamResponse`，响应头先于 body 行可用、返回前可被协程取消，结构化重试（429/503 决策）才可落地。
4. **rewind 校验 entryId 存在**：不存在抛 `IllegalArgumentException`（客观可校验、fail-fast）；位置语义仍不校验（§5.3）。改第一条消息 = 新建实例（§5.1），不提供回退到 root 的 API。
5. **活跃回合并发契约**：send 与 rewind / fork / update / refreshMcpTools / close 在活跃回合期间均抛异常；stop 是唯一例外（取消路径）。与 §5.2 并发 send 抛异常一致。
6. **McpClient.callTool 返回结构化 McpCallResult**：`isError: Boolean`（区分 MCP 工具执行错误与正常成功）+ `content: List<McpContentBlock>`（Text / Image / Resource），McpExecutor 可据此产出 Failure 而非 Success。
7. **toolRegistry 单一来源**：保留 `OkiaConfig.toolRegistry`，从 `OkiaDependencies` 删除，避免 loop 与 MCP 刷新使用不同 registry。
8. **McpDiscoveryListener 文档修正**：§5.13 从"并入 Hooks（时机面）"改为"删除，无 hook 替代"，观察走 `refreshMcpTools` / `getMcpDiscoverySnapshot`。

### 8.8 冻结前落地（2026-08-13）

签名冻结前按第五轮 CR 裁决收敛，全部为签名 / 契约 / 文档层：

1. **删除 `Okia.fork()` 与 `RealConversation.fork()`**：分支语义由下游 `export()` + `open(restore)` 自行实现，库不再提供 fork。连带删除 `Conversation.parentSessionId` / `SessionSnapshot.parentSessionId` / `RealConversation.parentSessionId`（fork 链的持久化产物，无生产者）。§4 / §5.3 / §5.9 / §5.13 同步；§8.4 #10、§8.6 #5、§8.7 #5 中 fork 相关内容随本条失效。
2. **取消契约修正**：`Aborted` 终态由协调器（`Okia.send`）在取消 job 后按 `StopCause` 产生，不经被取消 job 的返回值传递；`AgentLoop.run` 保持结构化并发，外部取消在 NonCancellable 清理后重新抛出。
3. **新增 `LLMErrorCode.UnknownTool`**（不可重试）：模型生成未知工具名时抛异常，回合 `Failed`；错误文案由 host 按 code 映射，核心不预设返回值。
4. **`McpContentBlock` 收窄**：删除 `Image` / `Resource`，仅保留 `Text`；结构化内容暂不实现，M1 客户端遇到非文本 block 报错，未来需要时新增子类。
5. **凭据脱敏**：新增 `isSensitiveHeader`（Authorization / Cookie / Proxy-Authorization / Set-Cookie，忽略大小写，参考 okhttp `internal/Util.kt`）+ `redactHeaders`；`HttpRequest` / `RequestSnapshot` / `OkiaConfig` / `McpServer` 覆盖 `toString()`，敏感 header 值与 apiKey 替换为 `██`，body 不输出内容。
6. **新增 `CompletionReason{Stop, Length}`**：`TurnResult.Completed` 改用新枚举，`StopReason` 的 Pending / ToolUse / Error / Aborted 不再可构造为 Completed。
7. **注释同步**：`RealConversation.rewind` 校验语义（存在性抛 IAE）、`Hooks.beforeInput` 语义（异步注入已移出）、§6 开放问题清理（仅剩 6.7）。

### 8.9 第六轮 CR 落地（2026-08-14）

签名冻结前的契约收敛（head `c7bc321` review 回应）：

1. **`TurnEvent` 补工具执行事件 + 改名**：`ToolCallEnded` → `ToolCallReady`（与协议层 `ProtocolEvent.ToolCallReady` 对齐，消除“组装完成 vs 执行终态”的歧义）；新增 `ToolRunning` / `ToolSucceeded` / `ToolFailed`（Succeeded/Failed 携带 `ToolCallOutcome`）。两个生命周期以前缀区分：`ToolCall*` = 产出调用意图，`Tool*` = 工具执行状态。
2. **依赖可见性修正**：`kotlinx-coroutines-core` / `kotlinx-serialization-json` 由 `implementation` 改 `api`（公开签名暴露 `StateFlow` / `SharedFlow` / `Flow` / `Json`）。
3. **核心接口成员改抽象**：`Okia` / `ChatProtocol` / `ProtocolCompatMapper` / `ToolRegistry` / `HttpEngine` / `ToolExecutor` / `McpClient` / `AgentLoop` / `SessionCodec` 的实例成员去掉 `= TODO()` 默认实现（companion 工厂方法保留 TODO 占位）；`Hooks` 保留默认空实现（hook 可选）。实现者漏实现改为编译期报错。
4. **`afterRequest` 形参修正**：`HttpResponse` → `StreamResponse`，时机 = 响应头到达、body 行消费前。`beforeRequest` / `afterRequest` 只覆盖模型流式路径（`AgentLoop` 经 `HttpEngine.stream`）；`unary()` 是 MCP 等其他网络请求，不触发 hook。
5. **`withCodec` 收窄**：`StringFormat` → `Json`。
6. **删除 `Message.User.timestamp`**：时间戳由会话树的 `ConversationEntry` / `MessageEntry` 唯一承载，消息内容不重复。
7. **凭据脱敏扩展**：`isSensitiveHeader` 从 4 个精确名扩展为精确白名单 + 片段匹配（`api-key` / `apikey` / `-key` / `-token` / `-secret` / `-signature` / `-auth`）；新增 `redactUrl`（query 值全脱敏）；`HttpResponse` / `StreamResponse` / `McpTransport.Http` 补 `toString()`；`Compat` 加 `sensitiveHeaderNames`（默认 empty），`HttpRequest` 加 `sensitiveHeaderNames` 字段（协议层从 Compat 填入）。
8. **host 契约注释**：`ToolRegistry` 注明活跃回合期间不得直接变更 registry（须经 `Okia.update`）；`InputHolder` 注明实现 write 时字段改私有 backing + 只读 getter。

### 8.10 第七轮 CR 落地（2026-08-14）

签名冻结前的最后一轮收敛：

1. **afterRequest 收窄为只读请求**：`afterRequest(request: HttpRequestHolder, response: StreamResponse)` → `afterRequest(request: HttpRequest)`。after 时机已无改写意义（请求已发出），且把 response（含 body 冷流）交给 hook 会让 loop 对 body 流的独占所有权退化为文档约束——hook 消费一次后 loop 收不到行、无归因静默失败。收窄后类型封死：before 可写（holder）、after 只读（HttpRequest），hook 碰不到 response。撤销 §8.9 #4 的 StreamResponse 形参。
2. **删除 `McpServerDiscoverySnapshot.stale`**：过期判定以 `McpDiscoveryState` 枚举为唯一权威（`UsingStaleCache` = 旧缓存可用但过期），不再保留独立布尔字段，消除双事实漂移。
3. **TurnStarted 归 AgentLoop 发**：回合事件序列全部由 loop 产出；`LoopRequest.input` 是原始用户文本（send 入参），供 loop 发 `TurnStarted(input)`，与 history 末尾 User 由构造顺序保证一致，非第二个独立来源。
4. **RealConversation 构造参数改名**：`entries` / `leafId` → `initialEntries` / `initialLeafId`。原参数与同名成员属性 `val entries` / `val leafId` 遮蔽，初始树状态不可达，实现期照直觉写 `get() = entries` 会无限递归（StackOverflowError）；改名后参数退为初始值语义。

### 8.11 第二轮实现落地（T2 垂直切片，2026-08-16）

`libs:okia` 实现阶段第二轮（T1 对话树 + T2 垂直切片）的契约回写。源码为准；实现细节的决策记录在 Progress.md D9-D15。

1. **消息成条时机与 live 不变量（§5.4 补充，2026-08-16 对齐）**：流式期间只更新 `live`，不碰 `history`（性能 + 不产生半截消息）；消息完整（该消息产出完成）才经 `LoopRequest.onCommit` 提交进 history。**不变量：live 非空 ⇒ history 不含该消息**，UI 渲染 = history 列表 + 末尾 live 打字机，不会出现重复。turn 结束（任何终态）时已产出的部分 commit 进 history（不丢消息）；T2 单消息场景下"消息完整"与"turn 结束"重合，T6 工具循环后每条模型往返消息各自在完成时 commit（含工具调用的消息在工具执行前 commit，Running 态从 history 推导）。
2. **close 契约补充**：close 后 send / rewind / update / export / config / close 均抛 IllegalStateException；活跃回合时 close 抛异常（§8.7 #5）；close 只取消 turnScope 并标记 closed，注入资源宿主所有不释放。
3. **export 活跃回合抛异常（§8.7 #5 列表外补充）**：回合中树在提交中，导出的快照不一致；与 rewind / update 一致性处理。
4. **终态中断流收集（T2 实测暴露）**：`AgentLoop` 收集协议流时，Completed / Error 终态必须以哨兵异常（`StreamTerminated`，非 CancellationException）中断 collect——无限流（SharedFlow 事件源）不自然结束，仅 `return@collect` 退出 action 会让 collect 继续挂起等下一事件，turn 永不完成。有限流（冷流）不受影响。取消路径仍走 CancellationException（§8.8 #2 不变）。
5. **外部取消传播**：调用方协程取消时 `send` 传播 CancellationException（协程取消语义优先），不产生 `Aborted(External)`；`StopCause.External` 的触发路径待真实消费者出现后定，枚举值保留。
6. **事件与状态投影同步（§5.4 落地）**：门面内部事件处理器同步做三件事——更新 live（StateFlow 投影）/ 转发调用方 onEvent / 发射 events SharedFlow。onCommit 原子做 appendAll + 清 live + 重新投影（一次 StateFlow 发射）。事件流 replay=0 + extraBufferCapacity=64（一次性事件，订阅晚不补发）。
7. **open 工厂状态**：`open(dependencies)` 已实现（测试注入点）；`open(protocol)` / `open(builder)` 留 T4/T8（依赖 M0 DeepSeek 默认协议与默认 McpClient / HttpEngine）。`ProtocolCompatMapper.from` 委托壳随 open(protocol) 一起在 T4 落地。
8. **默认资源占位**：`EmptyToolRegistry`（internal）为 config 未提供 registry 时的默认空实现；默认 HttpEngine 未实现，send 时 config.httpEngine 为空抛 IllegalStateException（明确失败，T8 落地）。

### 8.12 第三轮实现落地（T3 传输层 SSE，2026-08-16）

T3 实现期契约回写（调研参照：openai/codex `codex-rs`——`eventsource_stream` / `sse_stream` crate、transport 层非 2xx 拦截、`api_bridge.rs` 错误分类；pi 三份自写解析器潦草、无 content-type 校验，不作为范本）。实现细节的决策记录在 Progress.md D16-D20。

1. **StreamResponse sealed 化**：`data class` 三可空字段（statusCode / lines / errorBody）收敛为 `sealed interface` 两态——`Ok(statusCode: Int, headers, lines: Flow<SseLine>)`（2xx，SSE 解析入口）与 `Error(statusCode: Int, headers, body: String)`（非 2xx，body 全文文本）。statusCode 收窄为非空 Int；**传输失败（连接 / 超时）不在此表达**：`HttpEngine.stream` 是 suspend，网络错误抛异常（Kotlin 取消语义，与 codex transport 层同构），骨架"status 可空"的保守设计收窄。错误 body 通道由此闭合：非 2xx 时 HttpEngine 预读 body 文本进 `Error.body`（T8 默认实现保证），loop 不再需要从行流拼回错误文本。
2. **新增 `SseLineParser`**（transport 层公共类型）：`Flow<String>`（任意分块 UTF-8 字符串流）→ `Flow<SseLine>`。处理 `\n` / `\r\n` / `\r` 三种分隔符（含跨块 `\r\n`）、EOF 无换行 flush、流首 BOM 移除。行分类：注释行（`: 开头`）→ `SseLine(null)`，空行 → `SseLine("")`，其他 → `SseLine(原文)`。null / 空行保留在流中（§5.8 idle 检测的到达证据，不丢弃）。状态在 flow 构建器内创建，冷流无泄漏。
3. **新增 `SseEvent` + `SseEventParser`**（transport 层公共类型）：`Flow<SseLine>` → `Flow<SseEvent(data: String, event: String?)>`。严格 W3C 标准：空行 = 事件边界（dispatch）、data 字段多行用 `\n` 拼接、流结束时 data 缓冲非空的事件照常 dispatch（EOF flush）、data 缓冲为空字符串的事件丢弃。**event 字段透出**（MCP 等协议用 `event:` 过滤非 message 事件，codex rmcp-client 实证 `event: message` + `data: JSON-RPC`）；id / retry 为重连机制字段，LLM 与 MCP 均不使用，忽略。决策依据：Codex 内部因 data-only 聚合器（`codex-client/src/sse.rs`）服务不了 MCP，rmcp-client 被迫另用 `sse_stream` crate 重写——OKIA 一个聚合器服务模型流与 MCP 两端，避免重复。
4. **loop 前置校验（RealAgentLoop，T2 代码改动）**：响应按 sealed 分支——`Error` 直接 `Failed(LLMError)`，**不进 parseStream**（风控 HTML / JSON 错误不会被当 SSE 解析）；`Ok` 分支做 content-type 黑名单：`text/html`（忽略大小写，值前缀匹配）→ `Failed(Parse)`，其他（含缺失、`text/event-stream`、`application/json`）放行。content-type 检查是快速失败优化，正确性兜底仍是"流结束无 Completed → Parse 错误"（§8.11 #4 前的既有兜底）。
5. **非 2xx 错误码映射**：429 → `RateLimit`、401 / 403 → `Auth`、5xx → `Overloaded`、其他 → `Transport`。`Error.body` 截断 2000 字符进 `LLMError.message`（UI 详情非完整响应；错误文案仍由 host 按 code 映射，§8.8 #3 不变）。

### 8.13 T4 落地（2026-08-16）

1. **新增 `DeepSeekChatCompletionProtocol`**（protocol/ 包）：M0 协议实现，OpenAI 兼容格式。独立实现，不复用通用 OpenAI 层——DeepSeek 私有字段（reasoning_content 等）的调整局限在本类，不牵动通用逻辑。映射语义参考 pi openai-completions；产品策略不包含（重试 / 缓存 / 成本在其他层或下游）。
2. **Completed 语义确认（修正 §8.7 #2 候选列表）**：协议层 `ProtocolEvent.Completed` 是**单次模型流结束**（消息级）。finish_reason 映射：stop/end → Stop、length → Length、function_call/tool_calls → **ToolUse**；content_filter / network_error / 未知值 → `Error` 事件；EOF 无 finish_reason → `Error` 事件。ToolUse 时回合未结束（T6 工具循环执行工具后发起下一轮）；只有 Stop / Length 时 turn 层结束回合（TurnCompleted / TurnResult.Completed）。**T2 RealAgentLoop 对非 Stop/Length 判 "abnormal completion stopReason" 是工具循环未实现前的占位，T6 改为 ToolUse → 继续工具循环。**
3. **encodeToolResult 不加工内容**：`Message.ToolResult` 序列化为 role=tool 消息时 content = outcome.content 原样（null 用空串）。错误表达由下游在 outcome.content 决定，框架不做错误文本加工（原「Interrupted / Unknown 编码为错误文本」注释作废）。
4. **thinking 映射**：SSE `reasoning_content`（含 reasoning / reasoning_text 兜底）→ ThinkingDelta；`ThinkingSignature` 对 DeepSeek 不产出（无签名机制，签名是 Anthropic 语义）；assistant 历史回喂：thinking → `reasoning_content` 字段，无思考补空串（`requiresReasoningContentOnAssistantMessages`）。
5. **请求体要点**：assistant content 用普通字符串（避免模型镜像块结构）；空 assistant 消息（无文本无 tool_calls）跳过；tools 为 function 格式，inputSchemaJson 解析为 parameters（null 省略）；`stream:true` + `stream_options.include_usage`；usage 语义 = pi（input = prompt − cacheRead − cacheWrite）。
6. **工具调用分片**：delta.tool_calls 按 index 归属，id / name 增量补全，arguments 拼接；空 arguments 分片不发 Delta（对齐 pi）；EOF 按 index 顺序发 ToolCallReady 再发 Completed。
7. **Image 块**：buildRequest 抛 IllegalStateException（M2 前），异常消息说明；不写专门测试（用户裁决）。
