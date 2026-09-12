package com.niki914.zafiro.chat.runtime

import com.niki914.okia.conversation.Conversation
import com.niki914.okia.conversation.SessionSnapshot
import com.niki914.okia.event.TurnEvent
import com.niki914.okia.loop.TurnResult
import com.niki914.okia.message.AssistantMessage
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.ToolCallOutcome
import com.niki914.permission.Attempt
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Job

/**
 * 主进程对话公共观察契约（T-02，AC5/AC6 状态面）。
 *
 * 本文件唯一拥有共享边界类型：[RuntimeFact]、[RuntimeFactSink]/[NoOp]/[BoundFactEmitter]、
 * [ExecutionScope]、[TurnHandle]、[ExecutionOwner]、[ConversationOperation]、
 * [StopTrigger]、[OperationOutcome]（planning T-02）。后续组（reducer/executor/observer/
 * runtime 门面/日志）只引用，不重定义。
 *
 * 规则（design §9）：
 * - snapshot 只读：订阅无业务副作用；单次发布不可变，集合均为只读视图，
 *   发布侧负责防御性复制，不暴露内部可变状态。
 * - 身份显式：每个事实携带创建时捕获的 [TurnKey]/requestId，不读取全局“当前回合”。
 * - 原始类型复用：内容/结果沿用 OKIA（[ContentBlock]/[AssistantMessage]/
 *   [ToolCallOutcome]/[TurnEvent]/[TurnResult]/[Conversation]）与 permission-manager
 *   （[Attempt]）；无证据时填 [Reason.Unknown]，不伪造、不猜测。
 * - 无行为：本文件只有数据与接口，无引擎、无单例、无 Android/UI 依赖。
 */

// ── 身份 ────────────────────────────────────────────────────────────────

/** 一次执行的回合身份：每次执行由 executor 分配，不复用 Hook 侧 turnId。 */
data class TurnKey(
    val conversationId: String,
    val turnId: String,
    val epoch: Long,
)

/** 内容块身份：turn/attempt/messageOrdinal/index 四段，禁止只用 index。 */
data class BlockKey(
    val turn: TurnKey,
    val attempt: Int,
    val messageOrdinal: Int,
    val index: Int,
)

/** 任务来源：执行任务的归属入口。区别于 [CommandSurface]（命令调用者的来源标记）。 */
enum class EntrySource {
    HomeChat,
    Host,
}

/** 命令调用者：仅标记操作来源，不能伪装成任务所有者或替代调用校验。 */
data class CommandCaller(val surface: CommandSurface)

enum class CommandSurface {
    HomeChat,
    Host,
    Notification,
    Overlay,
}

/** 命令结果：过期目标不影响新回合，仅内部返回。 */
sealed interface CommandOutcome {
    data object Applied : CommandOutcome
    data object IgnoredStaleTarget : CommandOutcome
    data object UnsupportedAction : CommandOutcome
}

/**
 * 生命周期绑定：仅用于创建任务及旧入口兼容（parentJob 取消即连带取消），
 * 不限制其他端凭 [TurnKey] 操作该任务；调用者不因持有它成为 Job 所有者。
 *
 * [executionContext] 是集成方原执行作用域上下文（如 viewModelScope.coroutineContext / Service
 * scope 上下文），executor 以它承载调度器及其它元素；[parentJob] 始终是任务父 Job 与
 * 所有权归属。默认仅 [parentJob]（无调度器时由协程库默认调度器兜底），
 * 集成方必须传自己的实际作用域上下文，避免输出被默认迁到后台调度器。
 */
data class ExecutionOwner(
    val id: String,
    val source: EntrySource,
    val parentJob: Job,
    val executionContext: CoroutineContext = parentJob,
)

/** 提交输入：query 文本 + 图片路径引用（与 OKIA send 同形态）。 */
data class TurnInput(
    val query: String,
    val images: List<ContentBlock.Image> = emptyList(),
)

/**
 * 执行完成回执（内部能力）：只承载“执行已结束”与原始失败（取消/非取消）或 null。
 * 不暴露 Job/Deferred；调用侧只经 `ConversationRuntime.await(handle)` 消费。
 */
internal interface TurnCompletion {
    /** 等待执行结束；保留原始失败时原样抛出该 [Throwable]，否则正常返回。 */
    suspend fun awaitCompletion()
}

/**
 * 内部任务令牌：submit 的返回值，只做身份关联，不暴露 Job 所有权；
 * 并私有携带完成回执，使 `await(handle)` 在完成早于 await 时仍能取到原始失败，
 * 无需全局结果注册表或有界缓存。
 * 停止/响应一律走 runtime 命令并携带 [TurnKey]。
 */
class TurnHandle internal constructor(
    val key: TurnKey,
    internal val completion: TurnCompletion,
)

// ── 会话操作 ────────────────────────────────────────────────────────────

/** 原入口动作及其负载；只显式调用时执行，不自动触发。 */
sealed interface ConversationOperation {
    /** 首条用户输入随操作携带，供后端复现原 Room 建档的标题/预览与时序。 */
    data class Create(val firstUserInput: String) : ConversationOperation

    /**
     * 恢复目标持久身份；[snapshot] 为调用方已加载的原 [SessionSnapshot]，
     * 直接复用不触发第二次 DB 读取。仅按 ID 的调用方可省略，不做 ID 推断。
     */
    data class Restore(
        val persistedId: String,
        val snapshot: SessionSnapshot? = null,
    ) : ConversationOperation

    /** 切换目标持久身份；[snapshot] 语义同 [Restore]。 */
    data class Switch(
        val persistedId: String,
        val snapshot: SessionSnapshot? = null,
    ) : ConversationOperation

    data object Reset : ConversationOperation
}

enum class OperationKind {
    Create,
    Restore,
    Switch,
    Reset,
}

enum class OperationPhase {
    None,
    InProgress,
    Succeeded,
    Failed,
}

/** 原生命周期触发原因：ownerEnded 的入参，不作为其他端停止任务的前置条件。 */
enum class StopTrigger {
    OwnerCleared,
    HostTaskCancelled,
    BinderDied,
    ServiceDestroyed,
    SessionReset,
}

/**
 * 操作结果：成功（携带后端实际分配/绑定的 [OperationOutcome.Succeeded.persistedId]，无身份为 null）
 * 或原始失败原因。不从 OKIA 会话 id 推断持久身份。
 */
sealed interface OperationOutcome {
    data class Succeeded(val persistedId: String? = null) : OperationOutcome
    data class Failed(val reason: Reason) : OperationOutcome
}

// ── 快照 ────────────────────────────────────────────────────────────────

/**
 * 完整公共观察快照：version 单调递增，时间/身份/内容/原因自包含。
 * conversation 为可信 OKIA 内容源的只读引用，不复制可变历史仓库。
 */
data class ConversationSnapshot(
    val version: Long,
    val updatedAtMillis: Long,
    val session: SessionObservation,
    val conversation: Conversation?,
    val active: TurnObservation?,
    val lastTerminal: TurnObservation?,
)

data class SessionObservation(
    val runtimeConversationId: String?,
    val persistedId: String?,
    val operation: OperationKind?,
    val phase: OperationPhase,
    val reason: Reason?,
)

data class TurnObservation(
    val key: TurnKey,
    val source: EntrySource,
    val phase: TurnPhase,
    val attempt: Int,
    val blocks: List<BlockObservation> = emptyList(),
    val tools: List<ToolObservation> = emptyList(),
    val blockers: Set<Blocker> = emptySet(),
    val permissions: List<PermissionRequestObservation> = emptyList(),
    val reason: Reason?,
)

enum class TurnPhase {
    Preparing,
    Running,
    Waiting,
    Cancelling,
    Completed,
    Cancelled,
    Failed,
}

data class BlockObservation(
    val key: BlockKey,
    val kind: BlockKind,
    val phase: BlockPhase,
    val content: ContentBlock?,
    val partial: AssistantMessage?,
)

enum class BlockKind {
    Text,
    Thinking,
    ToolCall,
}

enum class BlockPhase {
    Started,
    Streaming,
    Ended,
    Interrupted,
}

data class ToolObservation(
    val block: BlockKey,
    val callId: String?,
    val phase: ToolPhase,
    val call: ContentBlock.ToolCall?,
    val outcome: ToolCallOutcome?,
    val reason: Reason?,
)

enum class ToolPhase {
    Intent,
    Arguments,
    Ready,
    Running,
    Waiting,
    Succeeded,
    Failed,
    Interrupted,
}

/** 可并存阻塞集合：只记录已有显式等待点，不猜任意 suspend。 */
data class Blocker(
    val id: String,
    val kind: BlockerKind,
    val toolCallId: String?,
    val requestId: String?,
    val retryDelayMs: Long?,
    val reason: Reason?,
)

enum class BlockerKind {
    Permission,
    ToolExecution,
    RetryBackoff,
    CapabilityPreparation,
}

data class PermissionRequestObservation(
    val requestId: String,
    val turn: TurnKey,
    val toolCallId: String?,
    val kind: PermissionKind,
    val target: String?,
    /** 发起渠道：工具/UI 渠道名或系统 [com.niki914.permission.Channel] 名。 */
    val channel: String?,
    val phase: PermissionPhase,
    /** 复用 permission-manager 库模型（不可变快照，不暴露引擎内部列表）。 */
    val attempts: List<Attempt> = emptyList(),
    val reason: Reason?,
)

enum class PermissionKind {
    ToolConfirmation,
    ScreenConsent,
    SystemPermission,
}

enum class PermissionPhase {
    Requested,
    Processing,
    Allowed,
    Denied,
    Unavailable,
    Failed,
    Cancelled,
}

/** 原因自包含：code/origin 必填；无证据用 [Unknown]，用户文案仍走旧映射。 */
data class Reason(
    val code: String,
    val origin: String,
    val detail: String? = null,
) {
    companion object {
        val Unknown = Reason("UNKNOWN", "NOT_PROVIDED", null)
    }
}

// ── 事实与汇点 ──────────────────────────────────────────────────────────

/**
 * 归约用原始事实：全部携带捕获时身份（[TurnKey]/requestId/操作负载），
 * 旧回合事实可结算其终态，不能覆盖新回合。身份缺证据时显式 unknown，
 * 不以全局“当前工具/当前回合”猜测并行请求。
 */
sealed interface RuntimeFact {
    /**
     * 执行开始：executor 在收集执行冷流前发出。reducer 据此置 active
     * （Preparing/Running）并记录 input/source；慢执行挂起期间 in-flight 可见，
     * 晚订阅者在首个 StreamEvent 前即见 active。
     */
    data class ExecutionStarted(
        val key: TurnKey,
        val source: EntrySource,
        val input: TurnInput,
    ) : RuntimeFact

    /**
     * 同源内容更新：OKIA Conversation 引用变化时（ensure/restore/switch 后）
     * 的新引用，与 `snapshot.conversation` 同源。reducer 只替换引用不重建内容；
     * 持久化器据此订阅同一内容源。会话级事实，不归属回合。
     *
     * 清空语义：reset/close 后无会话实例时两个字段均为 null，reducer 据此清空
     * 会话引用；不以空字符串或占位对象假装仍有内容。
     */
    data class ConversationUpdated(
        val runtimeConversationId: String?,
        val conversation: Conversation?,
    ) : RuntimeFact

    /** 旧 mapper 过滤前的原始流事件（TextEnded/ToolCallDelta/Ready/TurnAborted 均在此）。 */
    data class StreamEvent(
        val key: TurnKey,
        val attempt: Int,
        val event: TurnEvent,
    ) : RuntimeFact

    /** 终态：仅结算一次（Completed/Failed/Aborted/IdleTimeout）。 */
    data class StreamResult(
        val key: TurnKey,
        val attempt: Int,
        val result: TurnResult,
    ) : RuntimeFact

    /** 框架异常（配置错误等未进 TurnResult 的失败），补最终原因。 */
    data class ExecutionFailed(
        val key: TurnKey,
        val attempt: Int,
        val error: Throwable,
    ) : RuntimeFact

    /**
     * 取消请求：目标回合已收到取消请求（公开 stop / 适配器 cancel / owner 生命周期
     * 取消），归约据此进入 Cancelling。请求原因只在终态缺原因时兜底；引擎随后给出的
     * StopCause / 失败原因优先。已结算回合不接收本事实（不伪造取消请求）。
     *
     * [trigger] 非空 = 原 [StopTrigger] 生命周期来源（ownerEnded）；公开 stop / 适配器
     * cancel 为 null。[reason] 记录请求事实（含来源 code），不替代引擎终态原因。
     */
    data class CancellationRequested(
        val key: TurnKey,
        val reason: Reason,
        val trigger: StopTrigger? = null,
    ) : RuntimeFact

    /**
     * 能力准备阶段（executor 侧 refresh/工具/MCP 准备）显式开始：在该已知阶段上挂
     * [BlockerKind.CapabilityPreparation] 阻塞，与权限/工具/退避阻塞并存；不把任意
     * suspend 等待当作准备。结束见 [CapabilityPreparationEnded]。
     */
    data class CapabilityPreparationStarted(val key: TurnKey) : RuntimeFact

    /** 能力准备阶段结束（成功/失败/取消）：在 finally 清除该显式阻塞。 */
    data class CapabilityPreparationEnded(val key: TurnKey) : RuntimeFact

    /**
     * 会话操作事实（create/restore/switch/reset 进展与结果）。
     *
     * [persistedId] 为操作后端实际分配/绑定的持久化 ID（无回执为 null）。
     * 只用于 create 这类后端分配身份的场景，不从 OKIA 会话 id 推断 Room 身份。
     */
    data class OperationEvent(
        val operation: ConversationOperation,
        val phase: OperationPhase,
        val reason: Reason? = null,
        val persistedId: String? = null,
    ) : RuntimeFact

    /** 授权事实（工具确认/屏控/系统权限链请求与进展），按 requestId 归属回合。 */
    data class PermissionReported(
        val observation: PermissionRequestObservation,
    ) : RuntimeFact
}

/**
 * 事实汇点：同步轻量回调；实现不得抛异常影响业务（调用侧可隔离）。
 * reducer 的 `accept` 即此签名。已带身份的事实（[RuntimeFact] 全员 keyed）走这里。
 */
fun interface RuntimeFactSink {
    fun accept(fact: RuntimeFact)
}

/** 默认空汇点：无观察调用保持原行为（LLMController.stream 默认值）。 */
object NoOp : RuntimeFactSink {
    override fun accept(fact: RuntimeFact) = Unit
}

/**
 * 按执行绑定的观察上下文（Fix-1）：executor 为每次执行创建并唯一持有身份。
 * 纯身份快照，无行为；绑定动作本身由 executor 构造时完成。
 */
data class ExecutionScope(
    val key: TurnKey,
    val attempt: Int,
    val source: EntrySource,
)

/**
 * 按执行绑定的发射器（Fix-1）：解决 `stream(query, images, observer)` 无 TurnKey
 * 入参与 [RuntimeFactSink] 只收 keyed 事实的矛盾。身份在 executor 侧捕获一次，
 * 生产者（LLMController.stream 内部）只发无身份 raw 事件，永不读取全局“当前回合”。
 *
 * 类型兼容：直接作为 `observer` 实参传入，无需改 `stream` 签名（Group3 侧只调
 * `emit`，不碰 `accept`）；reducer/observer 侧收到的仍是 keyed [RuntimeFact]。
 *
 * 身份交接示例：
 * ```
 * // 调用侧（executor，唯一捕获点）：分配身份并绑定一次
 * val emitter: BoundFactEmitter = ... // key = TurnKey(cid, uuid, epoch) + attempt + source
 * llmController.stream(query, images, observer = emitter)
 *
 * // 生产者侧（stream 内部）：只发 raw 事件，不读全局当前回合
 * onEvent { raw: TurnEvent -> emitter.emit(raw) }
 * // 终态 / 未进 TurnResult 的框架异常同理
 * emitter.emit(result); emitter.emit(error)
 * ```
 *
 * in-flight 可见性：慢执行挂起期间，reducer 已收 [RuntimeFact.ExecutionStarted]
 * （executor 在收集冷流前发出），快照 active 置 Preparing/Running，不呈“无进展”假象。
 */
interface BoundFactEmitter : RuntimeFactSink {
    val scope: ExecutionScope

    /** 本执行是否已发出执行级终态事实（StreamResult/ExecutionFailed）；
     * 已结算执行不再接收取消/准备阶段事实（不伪造已结算回合的观察）。 */
    val settled: Boolean
    /** raw 流事件 → 带绑定身份的 [RuntimeFact.StreamEvent]。 */
    fun emit(event: TurnEvent)
    /** raw 终态 → 带绑定身份的 [RuntimeFact.StreamResult]。 */
    fun emit(result: TurnResult)
    /** 未进 TurnResult 的框架异常 → 带绑定身份的 [RuntimeFact.ExecutionFailed]，原异常保留。 */
    fun emit(error: Throwable)

    /**
     * 能力准备阶段开始/结束 → 带绑定身份的 [RuntimeFact.CapabilityPreparationStarted] /
     * [RuntimeFact.CapabilityPreparationEnded]。只允许标注已知准备阶段
     * （LLMController.stream 的 refresh），不得用于任意 suspend 等待。
     */
    fun emitPreparation(started: Boolean)

    /**
     * 取消请求 → 带绑定身份的 [RuntimeFact.CancellationRequested]；由 executor 在公开
     * stop / 适配器 cancel / owner 生命周期取消前发出（未结算时）。
     */
    fun emitCancellationRequest(reason: Reason, trigger: StopTrigger? = null)
}
