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
| `Conversation` | 数据结构维护者。内部 Mutex 竞争控制；Fork/Rewind 能力 |
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

**决策**：`Conversation` 独立类维护对话数据结构，内部 Mutex 竞争控制（KMP 下唯一同步方案 = `kotlinx.coroutines.sync.Mutex`）。结构为条目树（id / parentId / timestamp）+ 可变的 leafId 当前位置。`fork()` 复制当前 leaf 路径（节点不可变共享，修改互不影响）；`rewind(entryId)` 原地移动 leafId 指针，被跳过的尾部保留在树中。

**原因**：W3 "单独类维护数据结构 + 内部竞争控制 + Fork/Rewind"；fork 独立性由不可变性保证；rewind 后历史投影 = leaf 到 root 线性投影。

**先例**：pi `buildSessionPath(entries, leafId)`（leafId 显式 + fallback 到最后一条）、`createBranchedSession(leafId)`、SessionHeader version。

**持久化**：`SessionSnapshot(id, parentSessionId, leafId, version, entries)` 由 codec 接口持久化（存储位置 host 决定）。**leafId 必须持久化**（rewind 位置在重载后保持；null = 恢复为最后一条）。

### 5.4 UI 数据模型：StateFlow + SharedFlow（W1）

**决策**：库提供 `StateFlow<Conversation>` 作为持久性数据源（UI 观察它渲染全部内容），`SharedFlow` 提供失败等一次性事件。参考 MVI。

**原因**：下游开发者极可能用 Compose；状态即数据流比事件累计更 Kotlin 原生。

**流式语义**：
- **Text**：有一点变化就反馈给 UI（逐 delta 更新快照）
- **Tool**：arguments 组装完成（`ToolCallEnded`）之前**不进入 UI 状态**（不占位）；组装完成后出现工具块，块状态 = `Start → Running → 终态（ToolCallOutcome）`

**开放问题**：库内事件协议（TurnEvent，§5.15）保留与否——宿主 IPC（RenderFrame 流式回调）需要流式事件，倾向"事件为事实 + 状态流为投影"。

### 5.5 Tooling 契约（W4）

**决策**：
1. 从门面入口开始，内部方法全部 `suspend`（支持打断）
2. **Tooling 永不抛异常，总是产出工具结果**。自定义工具强制实现 `onInterrupt`（返回工具结果），中断判定 = executor 内部状态
3. 中断的资源清理是下游职责，库只提供回调时机（`beforeStop`）

**先例**：okai 骨架的 `ToolExecutor.interruptedOutcome`；PRD 4.4 中断收尾分工（未派发 → loop 标记；已派发 → executor 判定）。

### 5.6 ToolCallOutcome（5 态）

**决策**：

```kotlin
sealed interface ToolCallOutcome {
    data class Success(val content: String) : ToolCallOutcome
    data class Failure(val message: String, val content: String? = null) : ToolCallOutcome
    data class Intercepted(val reason: String) : ToolCallOutcome   // hook 拦截结果
    data class Interrupted(val content: String? = null) : ToolCallOutcome
    data class Unknown(val message: String, val content: String? = null) : ToolCallOutcome
}
```

**`Blocked` 删除的原因**：Blocked 是"审批拒绝"的具体语义，应由下游 hook 泛化（拒绝 = `Intercepted` 或 `Failure`）；okai 骨架的 Blocked 值被裁掉。
**`Intercepted` 新增的原因**：hook 拦截 ≠ 工具失败，UI 要区分；hook 不只给失败结果（可能给成功模拟、缓存命中、拦截）。机制语义，下游自由泛化。

工具块 UI 终态 = 这 5 态（Start/Running 是过程态，见 §5.4）。`ToolResult` 消息内嵌同一 outcome（无状态映射，中断语义在持久化恢复后可读）。

### 5.7 Provider 生命周期（W5）

**决策**：
1. 实例化时协议定死：`Okia.open<P : ChatProtocol>(protocolClass, builder)` + reified 重载 + 默认协议版本（kai 形态）
2. 协议作用域 == Okia 实例生命周期
3. **持久化与恢复无矛盾**：恢复时重新 `open<P>()` 提供 Provider；协议 id 不进会话数据
4. **`ProtocolRegistry` 删除**：id 解析无用途（host 自己知道自己用什么协议，Nexus 的 `LlmApiType` 存 Room、恢复时 `openSession` 重新 open）

**先例**：kai `Kai.open<P>` 泛型绑定；Nexus `LLMController.obtainSession`（apiType 变化 → close + 重建）。

### 5.8 分层与序列化边界（W5）

**决策**：上层（loop / Conversation / Hooks / UI）协议无关，只用自定 dataclass（`Message` / `ContentBlock` / `RequestSnapshot`）；数据到 `ProtocolCompatMapper` 及以下（`ChatProtocol.buildRequest` / `encodeToolResult`）才按 Provider 序列化。host 用抽象 dataclass 实例化、不碰网络 raw data → 切换协议无影响。

保留：`ChatProtocol`（id / withCodec / useApiKey / buildRequest / parseStream / encodeToolResult / compat）、`Compat` 矩阵（maxTokensField / thinkingFormat / retryableStatusCodes 等，M0 仅 DeepSeekCompat）、`ProtocolEvent`（协议无关中间表示，与库级事件两层映射）。

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

**注册位置**：注册给 `OkiaConfig`（builder DSL：`hooks += ...`），不注册给实例。**fork 继承**：fork 复制 config（含同一 hooks 列表，不可变 List 共享引用）；有状态 hook 的状态隔离是下游职责（状态外置）；fork 后可用 `update {}` 调整 hooks。先例：kai 的 `hooks {}` DSL 在 config；Pi/Codex 的 hooks 均在配置层。

#### 5.9.4 时机清单

| 时机 | before 形参 | after 形参 | 时序位置 |
|---|---|---|---|
| `Input` | `input: InputHolder` | `input: InputHolder, handled: Boolean` | 用户输入进入后 |
| `Serialization` | `request: SerializationHolder` | `request: SerializationHolder, httpRequest: HttpRequest` | 消息序列 → buildRequest 前后（约等于序列化前后） |
| `Request` | `request: HttpRequestHolder` | `request: HttpRequestHolder, response: HttpResponse` | HttpEngine 发送前后 |
| `ToolCall` | `call: ToolCallHolder` | `call: ToolCallHolder, result: ToolResultHolder` | 工具执行前后 |
| `Stop` | `calls: List<ToolCall>` | `—` | 停止流程开始前 / 完成后 |

用途映射：
- 异步 Terminal 注入 → `beforeInput`（§5.10）
- 审批/拦截/参数改写 → `beforeToolCall`（阻断机制见开放问题 6.1）
- 审计/埋点 → `afterToolCall`、`afterStop`（对称保留，埋点统计有用）
- 数据脱敏 → `beforeSerialization`（主战场，协议无关层）＋ `beforeRequest`（http 层兜底）
- kill-then-stop → `beforeStop`（§5.11）

**删除**：`onFork` / `onRewind`（fork/rewind 是 Conversation 内部同步数据结构操作，无外部动作可钩）；`InterceptorChain`（§5.13）。

#### 5.9.5 mutation holder

**决策**：所有 before 的可改数据走 holder 对象：字段只读暴露，写入走 `write` 方法并**记录签名字段**（可追溯最后写入者，审计友好）。**骨架期 holder 只声明字段，write 方法留空**（没有消费者，不设计 API）。holder 归 `hooks/` 子包。

**形态选择的背景**：mutation（Pi 做法：原地改 `event.input`，后续 handler 可见）vs 返回值传递（OkHttp interceptor 式）。用户从使用角度选 mutation（只有要改时才调用 write，比"每个 hook 想返回什么"负担小）；签名 write 解决工程上的可追溯性（"最后是谁改的"）。

### 5.10 异步 Terminal 注入 → beforeInput

**决策**：`beforeInput` 承担"异步任务完成通知注入"。

**Nexus 实证**（`agent-runtime/.../LLMController.kt:195-203`）：terminal 工具 `background=true + notify_on_complete=true` → `TerminalSessionPool.startAsync` 后台执行 → 完成时通知入队 → 下一次用户输入时 `drainPendingNotifications()` 把 `[IMPORTANT: Background process ...]` 拼接进 query 前缀再 send。当前是 **host 侧文本拼接，Kai 完全不知情**——正是要下沉进 `beforeInput` 的场景。

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
| `McpDiscoveryListener` | 并入 Hooks（时机面） |
| `ToolCallOutcome.Blocked` | §5.6（新增 `Intercepted`） |

**保留**：`ChatProtocol` / `Compat` / `ProtocolEvent` / `RequestSnapshot`（协议层）、`Message` / `ContentBlock` / `Usage` / `StopReason`、`Session` 树 + `SessionCodec` + leafId 持久化（§5.3）、`ToolExecutor` / `ToolRegistry` / `ToolCallContext` / `ToolDescriptor`、`McpClient` / `McpServer` / `McpExecutor` / `McpDiscoverySnapshot`（Nexus 重度使用：fingerprint 刷新 + PromptComposer 渲染）、`HttpEngine` + transport 数据类（KMP actual 点）、`LLMError` / `RetryPolicy`（Nexus 手工分类要下沉）。

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

| # | 问题 | 背景 | 候选 |
|---|---|---|---|
| 6.1 | `beforeToolCall` 阻断机制 | 阻断 = 决策（拒绝执行），参数改写 = 修改（mutation）——两种能力是否同形？ | A：全 `Unit` + holder 预留 outcome 字段（统一 mutation，推荐）；B：返回 `ToolCallOutcome?`（非 null 短路）；C：Xposed 式 holder 操作（`args.return = null` 思路） |
| 6.2 | `TurnEvent` 事件协议保留与否 | §5.15；宿主 IPC 需要流式事件 | A：保留事件 + StateFlow 投影（推荐）；B：仅 StateFlow（事件派生） |
| 6.3 | 重试归属 | Codex 传输层内建重试、turn 层 loop 内建；PRD 4.7 分层容错 | A：核心内建（RetryPolicy 在 config）；B：下沉为内置 hook（下游注册） |
| 6.4 | hooks 列表可变性 | config 不可变原则 vs update 热更新需求 | A：只读 `List<Hooks>` + builder 累积（推荐）；B：`MutableList` |
| 6.5 | 包名 | §1 | `com.niki914.okia`（已定） |
| 6.6 | `Clock` 去留 | §5.13 | 删除（kotlin.time.Clock）或保留接口（测试注入价值） |

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
5. **withCodec 参数类型 = `kotlinx.serialization.StringFormat`**：JsonCodec 删除后，kotlinx.serialization 的 `StringFormat`（Json 实现）为注入编解码器的标准入口。
6. **协议无关 dataclass 标注 `@Serializable`**：Message / ContentBlock / ToolCallOutcome / AssistantMessage / Usage / StopReason / ConversationEntry / SessionSnapshot 直接可序列化（§5.13 JsonCodec 删除的依据落地）。
7. **ToolCallContext.session → conversation**：命名对齐 §5.3，字段语义不变。
8. **holder 直接置于 `hooks/` 子包**：§5.9.5「holder 归 hooks/ 子包」按「hooks 包的子包」即 `com.niki914.okia.hooks` 落地。
9. **OkiaDependencies 移入顶层包**：Clock / ForceStopHook 删除后 runtime 包无剩余内容，依赖装配并入顶层（顶层共 4 个类型，满足 ≤5 约束）。
10. **M0 构建**：AGP 9 内置 Kotlin + `org.jetbrains.kotlin.plugin.serialization` 2.2.0 + kotlinx-serialization-json 1.7.3；`./gradlew :libs:okia:compileDebugKotlin` 通过。

### 8.3 骨架文件清单（38 文件）

```
com.niki914.okia/
├── Okia.kt / OkiaConfig.kt / OkiaDependencies.kt / TurnOptions.kt   （顶层 4 类型）
├── conversation/  Conversation.kt（条目树 + leafId + Mutex）、SessionCodec.kt（SessionSnapshot）
├── loop/          AgentLoop.kt（AgentLoop / TurnResult / LoopRequest）、LoopOptions.kt
├── tooling/       ToolExecutor.kt、ToolRegistry.kt（+ToolDescriptor/ToolKind）、ToolCallContext.kt
├── message/       Message.kt、ContentBlock.kt、ToolCallOutcome.kt（5 态）、Usage.kt
├── protocol/      ChatProtocol.kt、Compat.kt（+DeepSeekCompat）、ProtocolEvent.kt、ProtocolCompatMapper.kt、RequestSnapshot.kt
├── hooks/         Hooks.kt（10 时机）、InputHolder / SerializationHolder / HttpRequestHolder / ToolCallHolder / ToolResultHolder
├── mcp/           McpClient.kt、McpServer.kt、McpExecutor.kt、McpDiscoverySnapshot.kt
├── transport/     HttpEngine.kt、HttpRequest.kt、HttpResponse.kt、StreamResponse.kt、SseLine.kt
├── error/         LLMError.kt、RetryPolicy.kt
└── event/         TurnEvent.kt（+FinishReason / StopCause）
```
