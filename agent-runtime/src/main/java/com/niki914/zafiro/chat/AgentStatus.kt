package com.niki914.zafiro.chat

import com.niki914.logging.Logger
import com.niki914.okia.conversation.Conversation
import com.niki914.okia.conversation.MessageEntry
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 对话的粗粒度阶段，只供系统入口（通知栏、悬浮窗等）展示，
 * 不区分思考/工具/重试细节。
 */
enum class AgentPhase {
    Idle,
    Generating,
    ToolRunning,
    WaitingPermission,
}

/** 回合的结束方式。 */
enum class TurnOutcome {
    Completed,
    Failed,

    /** 用户打断，或回合尾没有终态事件。 */
    Interrupted,
}

/**
 * 进程内可监听的对话状态，字段与常驻通知的 UI 一一对应：
 * 标题 `Zafiro · [statusText]`、正文 [preview]、按钮见 [permissionId]。
 *
 * 同一时刻只有一轮对话（[LLMController] 是进程内单例），故状态是单一值，不携带回合身份。
 *
 * @property phase 当前阶段。
 * @property preview 正文：进行中为本回合 agent 的首句文本（尚未开口时为本回合提问），
 *   已完成为 agent 最后一句文本，失败/已停止/待命为空。
 * @property outcome 回合结束方式；仅在 [AgentPhase.Idle] 时有值，null = 待命。
 * @property permissionId 待确认的授权请求 id，仅在 [AgentPhase.WaitingPermission] 时非 null；
 *   供通知的允许/拒绝按钮结算，结算入口 [com.niki914.zafiro.chat.agentic.shell.ToolPermissionCoordinator.respond]。
 */
data class AgentStatus(
    val phase: AgentPhase = AgentPhase.Idle,
    val preview: String? = null,
    val outcome: TurnOutcome? = null,
    val permissionId: String? = null,
) {
    /** 标题里的状态文案。 */
    val statusText: String
        get() = when (phase) {
            AgentPhase.Generating -> "生成中"
            AgentPhase.ToolRunning -> "执行工具"
            AgentPhase.WaitingPermission -> "等待授权"
            AgentPhase.Idle -> when (outcome) {
                null -> "待命"
                TurnOutcome.Completed -> "已完成"
                TurnOutcome.Failed -> "失败"
                TurnOutcome.Interrupted -> "已停止"
            }
        }

    /** 通知标题。前缀与文案随 UI 定，放在这里是为了日志与通知同源。 */
    val title: String get() = "$TITLE_PREFIX$statusText"

    /** 日志行：与通知的标题 + 正文同形，按日志即可对账通知显示。 */
    val logLine: String get() = preview?.let { "$title | $it" } ?: title
}

private const val TITLE_PREFIX = "Zafiro · "

/**
 * 主进程内的对话状态广播点。
 *
 * 只做状态投影：不持有执行所有权、不做授权决定、不排队命令；
 * 停止与授权响应仍走各入口原有路径。
 *
 * 阶段来源：
 * - 执行侧 [LLMController.stream] 的回合开始（[onRoundStarted]，同时提供本回合提问）、
 *   流事件（[onEvent]）与回合结束（[onPhase]）；
 * - 授权等待（[com.niki914.zafiro.chat.agentic.shell.ToolPermissionCoordinator]）的
 *   [onPermissionPending]，等待期间对外固定呈 [AgentPhase.WaitingPermission]；
 * - 会话载入/新建（[LLMController.openSession]、[LLMController.resetConversation]）的
 *   [onConversationChanged]。
 */
object AgentStatusHolder {
    /** 日志 TAG：logcat 按此名过滤，见 `niki914_nexus_AgentStatus`。 */
    private const val LOG_TAG = "niki914_nexus_AgentStatus"

    /** 预览截断长度，对齐通知正文一行。 */
    private const val PREVIEW_MAX_CHARS = 120

    private val lock = Any()
    private val flow = MutableStateFlow(AgentStatus())

    /** 进程内唯一状态流；晚订阅立即取得当前值。 */
    val status: StateFlow<AgentStatus> = flow.asStateFlow()

    /** 会话树读取入口；单测替换，生产读 [LLMController.currentConversation]。 */
    internal var conversationSourceForTest: () -> Conversation? =
        { LLMController.currentConversation.value }

    private var phase: AgentPhase = AgentPhase.Idle
    private var roundQuery: String? = null

    /**
     * 回合开始时的会话树长度：划出本回合新增的消息。
     * 没有这道界限，回合刚开始（用户消息还没提交）时会读到**上一轮**的 agent 文本。
     */
    private var roundStartIndex: Int = Int.MAX_VALUE

    private var roundOutcome: TurnOutcome? = null
    private var permissionId: String? = null

    /**
     * 回合开始：记下提问与回合起点。
     *
     * 提问由执行侧显式传入——此刻树里还没有本回合的消息，取不到提问。
     */
    internal fun onRoundStarted(userText: String?) = publish {
        phase = AgentPhase.Generating
        roundQuery = singleLine(userText)
        // 取不到会话树时用 Int.MAX_VALUE：宁可不读 agent 文本，也不能读到上一轮的。
        roundStartIndex = conversationSourceForTest()?.history?.size ?: Int.MAX_VALUE
        roundOutcome = null
        permissionId = null
    }

    /** 流事件推进阶段与回合结果；不改变阶段的中间事件（思考结束、工具结算）忽略。 */
    internal fun onEvent(event: LlmStreamEvent) = publish {
        phaseOf(event)?.let { phase = it }
        when (event) {
            is LlmStreamEvent.Error -> roundOutcome = TurnOutcome.Failed
            LlmStreamEvent.Completed -> roundOutcome = TurnOutcome.Completed
            else -> Unit
        }
    }

    /**
     * 执行阶段收尾。回合结束时若没有终态事件（用户打断、回合抛异常），记为
     * [TurnOutcome.Interrupted]；已完成/失败的结果不被覆盖。
     */
    internal fun onPhase(next: AgentPhase) = publish {
        if (next == AgentPhase.Idle && phase != AgentPhase.Idle && roundOutcome == null) {
            roundOutcome = TurnOutcome.Interrupted
        }
        phase = next
    }

    /** 授权等待；[requestId] 为 null 表示等待结束。 */
    internal fun onPermissionPending(requestId: String?) = publish {
        permissionId = requestId
    }

    /**
     * 载入历史会话或新建会话：回到待命。
     *
     * 上一个会话的提问与回合结果都属于它，不能显示在新会话上。
     */
    internal fun onConversationChanged() = publish {
        phase = AgentPhase.Idle
        roundQuery = null
        roundOutcome = null
        permissionId = null
    }

    /** 单测复位：对象状态跨用例保留。 */
    internal fun resetForTest() {
        synchronized(lock) {
            phase = AgentPhase.Idle
            roundQuery = null
            roundStartIndex = Int.MAX_VALUE
            roundOutcome = null
            permissionId = null
            conversationSourceForTest = { LLMController.currentConversation.value }
            flow.value = AgentStatus()
        }
    }

    /**
     * 发布值未变化时提前返回：流式 token 事件不重发同一状态，避免订阅方被淹没。
     *
     * 判重基于投影后的 [AgentStatus]：阶段不变而正文或结果改变同样要发出。
     *
     * 每次实际变化输出一条 DEBUG 日志（`niki914_nexus_AgentStatus`），样式即通知的
     * 标题 + 正文；日志在锁外输出，不让 logcat 写入阻塞状态发布。
     */
    private fun publish(update: () -> Unit) {
        val next: AgentStatus
        synchronized(lock) {
            val previous = flow.value
            update()
            val idle = phase == AgentPhase.Idle
            // 进入空闲：授权请求属于该回合，随之清除。
            if (idle) permissionId = null
            next = AgentStatus(
                phase = if (permissionId != null) AgentPhase.WaitingPermission else phase,
                preview = previewOf(idle = idle, outcome = roundOutcome),
                outcome = roundOutcome.takeIf { idle },
                permissionId = permissionId,
            )
            if (next == previous) return
            flow.value = next
        }
        Logger.d(LOG_TAG, next.logLine)
    }

    // ── 事件 → 阶段 ─────────────────────────────────────────────────────

    /** 返回 null = 本事件不改变阶段。 */
    private fun phaseOf(event: LlmStreamEvent): AgentPhase? = when (event) {
        LlmStreamEvent.RoundStarted,
        is LlmStreamEvent.TextDelta,
        is LlmStreamEvent.ThinkingStarted,
        is LlmStreamEvent.ToolPending,
        is LlmStreamEvent.Retrying,
        -> AgentPhase.Generating

        is LlmStreamEvent.ToolRunning -> AgentPhase.ToolRunning

        // 工具结算与思考结束不回落：并行工具以最后开始的阶段为准，
        // 下一段文本或下一个工具意图再切回 Generating。
        is LlmStreamEvent.ThinkingEnded,
        is LlmStreamEvent.ToolSucceeded,
        is LlmStreamEvent.ToolFailed,
        -> null

        is LlmStreamEvent.Error,
        LlmStreamEvent.Completed,
        -> AgentPhase.Idle
    }

    // ── 正文 ────────────────────────────────────────────────────────────

    /**
     * 进行中取本回合 agent 的首句，尚未开口时用本回合提问；
     * 已完成取本回合 agent 的最后一句；失败/已停止/待命为空。
     */
    private fun previewOf(idle: Boolean, outcome: TurnOutcome?): String? {
        val conversation = conversationSourceForTest()
        if (!idle) return firstTurnText(conversation) ?: roundQuery
        if (outcome != TurnOutcome.Completed) return null
        return lastTurnText(conversation)
    }

    /** 本回合首个非空文本块；流式中的助手消息尚未进树，最后看 [Conversation.live]。 */
    private fun firstTurnText(conversation: Conversation?): String? {
        turnHistoryOf(conversation)?.forEach { entry ->
            textOf(entry.message)?.let { return singleLine(it) }
        }
        return conversation?.live?.let { textOf(it.content) }
    }

    /** 本回合最后一个非空文本块。 */
    private fun lastTurnText(conversation: Conversation?): String? {
        conversation?.live?.let { live -> textOf(live.content)?.let { return singleLine(it) } }
        val turn = turnHistoryOf(conversation) ?: return null
        for (index in turn.indices.reversed()) {
            textOf(turn[index].message)?.let { return singleLine(it) }
        }
        return null
    }

    /**
     * 本回合新增的消息（最后一条用户消息之后的条目）。
     * 本回合的用户消息尚未提交时返回 null——那时「最后一条用户消息」属于上一轮。
     */
    private fun turnHistoryOf(conversation: Conversation?): List<MessageEntry>? {
        val history = conversation?.history ?: return null
        val lastUser = history.indexOfLast { it.message is Message.User }
        if (lastUser < roundStartIndex) return null
        return history.subList(lastUser + 1, history.size)
    }

    /** 首个非空文本块；思考块与工具参数不进正文。 */
    private fun textOf(message: Message): String? =
        (message as? Message.Assistant)?.let { textOf(it.message.content) }

    private fun textOf(blocks: List<ContentBlock>): String? =
        blocks.filterIsInstance<ContentBlock.Text>().firstOrNull()?.text?.takeIf(String::isNotBlank)

    /** 压成单行并截断；截断处不切开代理对。 */
    private fun singleLine(raw: String?): String? {
        val collapsed = raw?.replace(WHITESPACE, " ")?.trim().orEmpty()
        if (collapsed.isEmpty()) return null
        if (collapsed.length <= PREVIEW_MAX_CHARS) return collapsed
        val end = if (Character.isHighSurrogate(collapsed[PREVIEW_MAX_CHARS - 1])) {
            PREVIEW_MAX_CHARS - 1
        } else {
            PREVIEW_MAX_CHARS
        }
        return collapsed.substring(0, end)
    }

    private val WHITESPACE = Regex("\\s+")
}
