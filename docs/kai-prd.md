# Kai PRD — Kotlin AI Infra / Agent Loop 库（重设计）

> 状态：草案 · 目标读者：Kai 与 Nexus 的维护者
> 参考架构：pi（`packages/ai` + `packages/agent` + `packages/coding-agent` 分层）、OpenAI Codex CLI（`codex-core` / `codex-protocol` / `codex-model-provider` / `codex-tools` / `codex-mcp` / `codex-hooks` 分层）
> 前提：本文描述的是一个**尚未实现**的库。不参考现有 Kai（s3ss10n）的 API 设计；仅吸收其使用方（Nexus）验证过的需求与 Pi/Codex 验证过的架构模式。

---

## TODOs

[ ] 确认 Nexus 层关于 Fork、对话消息方面的修改是否牵动 Kai 的 history 相关设计。Kai 是否支援树状数据结构等（Coding Agent 的对话在进行 fork 之后数据结构就是树）
[ ] 确认 Nexus 持久化与 Protocol 切换相关的矛盾，是否存在矛盾等

## 0. 可用资源

Codex-CLI、Pi 是知名的 Coding Agent 仓库，无论是最新的 Provider 接入还是架构设计都可以参考这两个库的优点

## 1. 背景与动机

Nexus 当前把 Kai 当"回合执行引擎"使用，暴露出的真实需求是：

- **回合驱动**：一条用户输入 → LLM ↔ 工具多轮循环 → 终态；流式事件输出；stop/回滚/超时
- **协议翻译**：同一套库跑不同 LLM API（当前只需要 DeepSeek，将来 OpenAI/Anthropic/其他）
- **工具调用**：本地工具与 MCP 工具；**但 Nexus 缺少对工具调用链的管理/拦截能力**——这是本 PRD 的核心新增能力
- **无人值守**：语音助手场景下没有用户盯着屏幕确认，loop 必须自洽（错误分类、重试、超时、部分结果回滚）

现有实现的痛点（仅作为需求来源，不作为设计依据）：

1. **无工具调用拦截器链**：工具调用要么本地执行、要么委托，没有可插拔的"调用前审查/改写/重试/审计"管线
2. **错误分类不可移植**：HTTP 状态码被碾进异常消息字符串；错误分类逻辑（可重试/配额/溢出）散在消费方
3. **流式事件模型单薄**：无 thinking 事件、无结构化 partial 快照、无段边界语义
4. **重试缺失**：无分层重试（传输层 / 回合层），无退避与 jitter
5. **idle 检测语义错误**：按"事件间隔"而非"网络活跃度"计时，长思考回合、耗时 Tooling 会被误杀
6. **职责上溢**：MCP 刷新决策、回合串行化、资源清理时序等工作被迫由消费方（Nexus）承担

## 2. 定位与边界

**Kai 是什么**：一个 **协议无关的 LLM 回合执行引擎 + AI 基建库**，核心为

- 统一消息/内容模型（多模态 content block）
- Provider 抽象（协议 + 兼容矩阵）
- 流式事件协议
- Agent loop（回合驱动、工具循环、段回滚、stop/超时）
- **工具调用拦截器链（interceptor chain）**
- 工具执行抽象（本地 / MCP -- MCP 在现有的 Kai 已有实现，但经过调研 MCP Client 有很多讲究，在版本、网络层协议如 SSE、WSS 等都有差异，而有一些本地 MCP 依赖到 Node，这个在安卓 / JVM 无法实现。MCP 相关强烈建议参考 Pi 等）
- 分层容错（重试、idle、错误分类）
- 上下文管理原语（预算估算、compaction 口子）

**Kai 不是什么**（边界，防上溢）：

- 不内置提示词构建（system prompt 组装是 host 职责）
- 不内置工具实现（终端、无障碍、文件系统——host 注入）
- 不内置 UI/渲染（事件消费是 host 职责）
- 不内置持久化存储后端（提供可序列化会话模型与 codec 接口，存储位置由 host 决定）
- 不内置资源生命周期管理（工具进程 kill、连接池——host 注入的运行时资源由 host 管理，Kai 提供生命周期回调口子）

**复用性目标**：同一定位可服务 语音助手 / 桌面 CLI / 测试桩 / 后续多模态与实时场景。衡量标准：**库不依赖任何 Nexus 类型**；所有 host 特性通过接口、拦截器、配置注入。

## 3. 分层架构

```
┌────────────────────────────────────────────────────────┐
│ Host 层（Nexus / 其他）                                  │
│ 提示词构建 · 工具实现 · UI/渲染 · 持久化 · 资源生命周期    │
└───────────────┬────────────────────────────────────────┘
┌───────────────▼────────────────────────────────────────┐
│ Kai 消费面（公开 API / 事件流 / 配置）                   │
└───────────────┬────────────────────────────────────────┘
┌───────────────▼────────────────────────────────────────┐
│ Loop 层      AgentLoop：回合驱动 · 工具循环 · 段回滚     │
│              stop/超时 · 拦截器链调度 · 重试策略         │
├────────────────────────────────────────────────────────┤
│ 会话层       Session：历史 · 并发契约 · 上下文预算/压缩  │
├────────────────────────────────────────────────────────┤
│ 工具层       ToolRegistry · ToolCall 拦截器 · 执行器抽象 │
│              （LocalExecutor / McpExecutor / 扩展点）   │
├────────────────────────────────────────────────────────┤
│ 消息层       ContentBlock 模型（text/thinking/image/    │
│              toolCall/toolResult）· 事件协议 · 序列化    │
├────────────────────────────────────────────────────────┤
│ Provider 层  ChatProtocol（请求构建+流解析）·            │
│              Compat 矩阵 · Transport（结构化 HTTP）      │
└────────────────────────────────────────────────────────┘
```

对齐参考：Provider/消息/事件 ≈ pi-ai；Loop/会话 ≈ pi-agent + codex-core（subset）；工具拦截/审批 ≈ codex `tools/approvals` + `hooks`；协议事件 ≈ codex-protocol `TurnItem`；Host 层 ≈ pi-coding-agent + codex-tools。

## 4. 核心能力

### 4.1 统一消息与内容模型（多模态预留）

采用 pi 的 content block 模式，**一个模型贯穿 loop / 事件 / 历史 / 序列化**：

```kotlin
sealed interface ContentBlock {
    data class Text(val text: String, val signature: String? = null) : ContentBlock
    data class Thinking(val text: String, val signature: String? = null) : ContentBlock
    data class Image(val data: String, val mimeType: String) : ContentBlock      // 多模态口子
    data class ToolCall(val id: String, val name: String, val argumentsJson: String) : ContentBlock
}
```

- 消息模型对齐 pi：`User` / `Assistant` / `ToolResult` 三种具体消息类型，无通用角色+内容基类；`ToolResult` 是消息类型，不是 content block，其 `content` 为任意文本（工具结果不强制 JSON，Nexus 现有工具即返回纯文本）；`ToolResult` 内嵌统一的 `ToolCallOutcome`（Success/Failure/Blocked/Interrupted/Unknown，拦截器链、执行器、历史共用同一类型，无状态映射），中断语义在持久化恢复后仍可读（超出 pi：pi 仅 content+isError，无 force-only stop）
- Assistant/User 消息 = `List<ContentBlock>`（支持同一消息内 text+thinking+image+toolCall 并存，Anthropic 风格）
- 多模态口子：Image block 与 provider 序列化映射在 Provider 层完成；DeepSeek 阶段该 block 置空或报"不支持"

### 4.2 流式事件协议

对齐 pi 的事件集与 codex TurnItem 的思路——**事件统一携带 `partial` 快照，UI 可流式渲染或按 end 渲染**：

```kotlin
sealed interface TurnEvent {
    data class TurnStarted(val input: String) : TurnEvent
    data class TextStarted(val index: Int, val partial: AssistantMessage) : TurnEvent
    data class TextDelta(val index: Int, val delta: String, val partial: AssistantMessage) : TurnEvent
    data class TextEnded(val index: Int, val content: String, val partial: AssistantMessage) : TurnEvent
    data class ThinkingStarted(val index: Int, val partial: AssistantMessage) : TurnEvent
    data class ThinkingDelta(val index: Int, val delta: String, val partial: AssistantMessage) : TurnEvent
    data class ThinkingEnded(val index: Int, val content: String, val partial: AssistantMessage) : TurnEvent
    data class ToolCallStarted(val index: Int, val partial: AssistantMessage) : TurnEvent
    data class ToolCallDelta(val index: Int, val delta: String, val partial: AssistantMessage) : TurnEvent
    data class ToolCallEnded(val index: Int, val toolCall: ToolCall) : TurnEvent
    data class RetryScheduled(val attempt: Int, val maxAttempts: Int, val delayMs: Long, val reason: String) : TurnEvent
    data class TurnCompleted(val message: AssistantMessage, val reason: FinishReason) : TurnEvent
    data class TurnFailed(val message: AssistantMessage, val reason: FinishReason) : TurnEvent  // reason: error|aborted
}
```

- `contentIndex` 支持多内容块（thinking 与 text 在同一消息内并行/分段）
- `FinishReason`：`stop | length | toolUse | error | aborted | idleTimeout | retryExhausted`
- 事件协议是**库的对外契约**，UI、遥测、测试桩都消费它

### 4.3 Provider 抽象 + 兼容矩阵

```kotlin
interface ChatProtocol {
    fun withCodec(codec: JsonCodec): ChatProtocol
    fun buildRequest(snapshot: RequestSnapshot, history: List<Message>): HttpRequest
    fun parseStream(raw: Flow<SseLine>): Flow<ProtocolEvent>   // TextDelta/ThinkingDelta/ToolCallReady/Error/Completed
    fun encodeToolResult(toolCall: ToolCall, outcome: ToolCallOutcome): Message   // isError 由 outcome 派生；Interrupted/Unknown 编码为错误文本
}

interface Compat {   // 对齐 pi OpenAICompletionsCompat，每 provider 一份
    val maxTokensField: MaxTokensField          // max_tokens | max_completion_tokens
    val supportsReasoningEffort: Boolean
    val thinkingFormat: ThinkingFormat          // deepseek(thinking:{type}+effort) | openai(reasoning_effort) | ...
    val requiresReasoningContentReplay: Boolean // tool 场景必须回传 reasoning_content（DeepSeek 400 规则）
    val requiresAssistantAfterToolResult: Boolean
    val retryableStatusCodes: Set<Int>          // 默认 408/409/429/5xx
}
```

- `ProtocolEvent` 是协议无关的中间表示（对齐现有 pi 的做法），`TurnEvent` 是库级事件，两层映射
- **M0 仅实现 `DeepSeekCompat`（OpenAI 兼容协议 + thinking/reasoning_effort/max_tokens/回传策略）**，接口天然容纳后续 OpenAI/Anthropic

### 4.4 Agent Loop

- `AgentLoop.run(request, onEvent): TurnResult`：无状态回合引擎，LLM ↔ 工具循环直到终态；history 入、本回合消息序列（assistant 与工具结果按发生顺序交错，单个 assistant 消息内 text/thinking/toolCall 混合）出，由 Okai 提交到 Session
- **段（segment）原子性**：每轮 LLM 调用为一个段；段内失败（且未产出任何 content）可安全重试或回滚；已产出部分内容则按策略提交或丢弃——由 loop 持有边界，host 不必感知
- 并发契约显式化：`ConcurrencyMode`（`Reject` / `Replace` / `Queue`），由 config 声明（消除 host 自行加锁）；Okai 持有单活跃 turn
- **stop 为 force-only**：所有取消来源（用户 stop / Replace / 外部取消 / close）走 `Okai` 统一协调路径：记录 `StopCause`（`UserStop` / `Replace` / `External`）→ 调用 `ForceStopHook`（至多一次，参数为本 turn 已派发的工具调用；**先 kill 后 cancel**——解除阻塞工具对协程取消的不响应，stop 不会因阻塞工具而永不返回）→ 取消子 job 并 join。取消传播到所有挂起点（流收集、工具执行、重试延迟）；loop 在 `NonCancellable` 中收尾：为 partial assistant 消息中所有 pending tool call 产出终态 outcome（未派发进链的由 loop 直接标记 `Interrupted`；已派发的由 executor 的 `interruptedOutcome` 判定 `Interrupted` / `Unknown`；未到达 `ToolCallReady` 的不持久化）→ 返回 `TurnResult`，history 对下一轮 well-formed，模型可见中断。`AgentLoop.run()` 永不抛出 CancellationException（loop 无法区分内部 stop 与外部取消），取消以 `FinishReason.Aborted` + `StopCause` 表达；`Okai.send()` 持有子 job，任意路径拿到 `TurnResult` 并在 NonCancellable 中提交 session，若调用方协程被外部取消，提交后原样重抛。idle timeout 返回 `IdleTimeout`（cause 为空）
- idle 检测：**按传输层活跃度计时**（SSE 任意帧含 keep-alive 注释），而非事件间隔；长思考/长工具执行不误杀

### 4.5 工具调用拦截器链（核心新增）

对齐 Codex 的 approvals/hooks 管线与 OkHttp 的 interceptor 思想。**工具调用在 loop 内的执行路径是：**

```
LLM ToolCallReady
  → 拦截器链（有序，可插拔，可短路）：
      ① ApprovalInterceptor    （host 注入：自动放行/审查/拒绝）
      ② RewriteInterceptor     （参数改写/填充，如注入上下文）
      ③ RetryInterceptor       （幂等工具失败重试策略）
      ④ AuditInterceptor       （日志/遥测/用量统计）
      ⑤ CacheInterceptor       （可选结果缓存）
  → ToolExecutor（LocalExecutor / McpExecutor / host 扩展）
  → 结果回传（ToolResult 消息 → 下一段 LLM 调用）
```

```kotlin
interface ToolCallInterceptor {
    suspend fun intercept(call: ToolCallContext, chain: ToolCallChain): ToolCallOutcome
    // ToolCallContext: id/name/arguments/session/attempt
    // chain.proceed(call) 进入下一个拦截器或执行器
}
```

- **顺序 = 注册顺序**；任一拦截器可返回终态（拒绝/失败）短路链路
- host（Nexus）用拦截器实现：无障碍操作的"先读屏再执行"强制、自定义工具审批、运行中工具状态上报、失败重试策略——**这些现在散落在 Nexus 工具实现里，统一收口到链上**
- 拦截器不感知具体 executor 类型（本地/MCP/未来沙箱通用）
- **中断收尾**：按事实归属分工——未派发进链的 call 由 loop 直接标记 `Interrupted`（从未执行是 loop 的直接事实）；已派发的 call 由 executor 的 `interruptedOutcome(call)` 按自身内部状态判定 `Interrupted`（未开始或本地已终止）或 `Unknown`（远端可能已执行，禁止自动重试）；loop 在取消收尾中组装进 history（ToolResult 内嵌同一 outcome，无状态映射）；未到达 `ToolCallReady` 的调用不持久化（对齐 pi：未完成的调用不进历史）

### 4.6 会话与上下文管理

- `Session`：条目树（`id` / `parentId` / `timestamp`）+ 可变的 `leafId` 当前位置 + codec 接口（host 决定存储）；`history` = leaf 到 root 的线性投影，loop 消费；`SessionSnapshot` 持久化 `leafId`（null = 恢复为最后一条，对齐 pi 的 leafId 显式恢复 + fallback）与 `version`，rewind 位置在重载后保持
- **fork / rewind**：`Okai.fork()` 返回新实例承载新会话——复制当前 leaf 路径（节点不可变共享，独立性由不可变性保证），`parentSessionId` 指回源会话；`Okai.rewind(entryId)` 原地移动 leaf，被跳过的尾部保留在树中，可再次 rewind 或 fork。对齐 pi `createBranchedSession` / `branch`，不做同实例会话切换
- **载入对话**：宿主通过 `Okai.open(dependencies, builder)` 传入已恢复的 Session 实例化（会话切换 = 新建实例，不提倡实例内切换）
- 上下文预算原语：token 粗估（chars/4）+ usage 回填；溢出错误分类（正则库，对齐 pi `overflow.ts` / codex 溢出分类）
- **compaction 口子**：提供 `ContextPolicy` 接口（触发阈值、压缩回调），**M0 不实现**，host 层（Nexus 提示词层）将来实现
- 定位：Kai 提供原语，策略决策在 host——对齐 pi 把 compaction 放在 coding-agent 层

### 4.7 分层容错

- **传输层重试**：结构化 HTTP（status/headers 可用）→ retryable 状态码 + `Retry-After`/`retry-after-ms` 尊重 + 指数退避（`base·2^n`，上限 + jitter）→ 可中断（协程取消）
- **回合层重试**：`TurnFailed(reason=error)` + 错误分类（quota/溢出不可重试，overloaded/网络/流中断可重试）→ 段首重试；发出 `RetryScheduled` 事件
- 错误分类产出**结构化错误码**（`LLMError.Code`：`auth | quota | rateLimit | overloaded | contextOverflow | transport | parse | retryExhausted`），host 直接映射 UI 文案，不再解析异常字符串

## 5. 可从 Nexus 下沉的逻辑（复用性收益清单）

| Nexus 现状（决策/补偿逻辑） | 下沉到 Kai 的形式 |
|---|---|
| 工具调用策略散落（读屏前置、审批、失败重试提示） | **ToolCallInterceptor 链**（4.5）——host 只注册拦截器 |
| `turnMutex.tryLock` 串行化 | Session 并发契约显式化（4.4） |
| `stop` 前杀进程 workaround | force-only stop + `ForceStopHook` 终止回调口子（4.4） |
| `llmIdleTimeoutSeconds=50` 硬编码猜测 | 传输层活跃度 idle 检测（4.4） |
| `lastMcpServersFingerprint` + 刷新决策 | Session 内 MCP 生命周期管理（配置即刷新，host 只管配置） |
| `LlmErrorCode` 手工分类 + 异常字符串解析 | 结构化错误码 + 分类库（4.7） |
| `ChatTurnJsonCodec` 手工序列化 | 统一消息模型自带 codec（4.1） |
| `toUserErrorMessage` 文案映射 | 错误码 → host 文案模板（4.7） |
| `LlmStreamCollectors` 投影器去重修补 | `partial` 快照语义（4.2），host 免去重 |

明确**不下沉**（保持 host 职责）：PromptComposer 提示词、工具实现（terminal/python/accessibility）、ConversationRepo 存储后端、RenderFrame 渲染、PyRuntime/TerminalSessionPool 资源生命周期（Kai 只给回调口子）。

## 6. 里程碑

| 里程碑 | 范围 | 验收 |
|---|---|---|
| **M0 — DeepSeek 跑通（本 PRD 的首要交付）** | 统一内容模型（text/thinking/toolCall/toolResult）· 流式事件协议 · DeepSeek 协议 + compat（thinking/reasoning_effort/max_tokens/回传策略）· Agent loop（tool 循环 + 段原子性）· **拦截器链骨架**（approval/rewrite/audit 三个内置通用拦截器）· 传输层重试 + 回合层段首重试 · 传输活跃度 idle · Session 序列化 | Nexus 替换现有接入，DeepSeek 思考可见、工具循环带拦截器、429/5xx 自动退避、长思考不误杀；无回归 |
| M1 — 多 provider 与 MCP | OpenAI / Anthropic 协议 + 各自 compat；MCP executor（复用现有 client，挂到拦截器链后）；上下文预算原语 | 新增 provider 只加一个协议文件 + 一份 compat |
| M2 — 多模态与扩展 | Image content block 贯通 provider 序列化；实时（realtime）事件口子；compaction 策略接口落地示例 | 多模态输入输出可跑通一个 demo host |

## 7. 非目标（本 PRD 明确不做）

- 不做完整提示词工程/系统提示词构建（host 层）
- 不做 UI 组件（host 层；Kai 只发事件）
- 不做具体工具实现（host 层；Kai 提供执行器抽象）
- 不做沙箱/安全执行（execpolicy 属 host 场景；Kai 留执行器扩展点）
- 不追求与 pi/codex 行为 1:1；只吸收架构分层与接口模式

## 8. 成功标准

1. **可移植**：Kai 模块零依赖 Nexus 类型；一个新 host（如桌面 CLI demo）只写提示词/工具/渲染即可复用
2. **可测**：协议解析、事件序列、拦截器链、重试/回滚均有独立 JVM 单测（golden fixtures 取自 DeepSeek 官方文档样例，或者参考 Pi 的代码来抄协议，因为这些库的支持又及时又准确，照着协议写反而不一定）
3. **可控**：工具调用全路径可见、可拦截、可审计（拦截器链全覆盖）
4. **可靠**：DeepSeek 场景下 429/5xx 自动退避、长思考不超时误杀、部分结果语义明确
5. **可扩展**：新增 provider / 多模态 / 新工具类型均不触碰 loop 核心代码
6. JVM 单元测试：从上到下，包括门面消费方的各种 Tooling、MCP 之类的都能成功跑通