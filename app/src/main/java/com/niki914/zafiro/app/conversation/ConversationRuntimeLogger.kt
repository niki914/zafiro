package com.niki914.zafiro.app.conversation

import com.niki914.logging.Level
import com.niki914.logging.Logger
import com.niki914.okia.conversation.Conversation
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.zafiro.chat.runtime.BlockKind
import com.niki914.zafiro.chat.runtime.BlockObservation
import com.niki914.zafiro.chat.runtime.ConversationRuntime
import com.niki914.zafiro.chat.runtime.ConversationSnapshot
import com.niki914.zafiro.chat.runtime.PermissionRequestObservation
import com.niki914.zafiro.chat.runtime.Reason
import com.niki914.zafiro.chat.runtime.TurnObservation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 日志输出接缝：生产默认 [LoggerSink]，Group11 测试注入记录型 sink。
 * [log] 为同步调用；实现抛异常不得影响采集（采集侧隔离）。一次 snapshot 可能调用多次
 * （见 [ConversationRuntimeLogger] 的有界分片契约）。
 */
fun interface ConversationRuntimeLogSink {
    fun log(message: String)

    /**
     * 该 sink 当前是否会真的输出。false 时上游跳过格式化，不构造任何记录。
     * 默认 true（自定义 sink 自行决定），生产 [LoggerSink] 跟随 [Logger] 门面。
     */
    fun isEnabled(): Boolean = true
}

/** 生产默认：复用既有 [Logger]（DEBUG 级；显式调试摘要不落任何持久化设置）。 */
object LoggerSink : ConversationRuntimeLogSink {
    override fun isEnabled(): Boolean = Logger.isEnabled(TAG, Level.DEBUG)

    override fun log(message: String) = Logger.d(TAG, message)
}

/**
 * 对话运行时独立日志消费者（T-07，AC7）。
 *
 * - 只收 [ConversationRuntime.snapshot]：无事件收集、无状态重建、无执行流 collect，
 *   不驱动任何保存或命令。
 * - 晚订阅完整：订阅 StateFlow，立即取得当前完整快照（含仅剩 lastTerminal 的空闲态）。
 * - 慢消费不反压：StateFlow 合并版本，收集器在注入 [dispatcher] 上运行，永不阻塞执行；
 *   允许跳过中间 version、最终状态正确。消费者 Job 仍是 scope 子 Job，寿命跟随 scope。
 * - **有界分片**：Android [com.niki914.logging.LogcatBackend] 把整串交给 `Log.d`，超长会
 *   被平台截断而丢状态。同一 snapshot 按逻辑字段切成多条记录，每条都在
 *   [MAX_RECORD_BYTES] UTF-8 字节以内，不截断列表尾部；分片按码点切分，保留
 *   Unicode/代理对边界。记录契约：
 *   `v=<version> <i>/<n><body>` —— 每条都带同源 version 与 part 序号/总数，顺序即原单行
 *   字段序；测试按 version 收集全部 `i/n` 再拼接可还原完整内容。
 * - 完整状态面：会话身份/操作、原内容源 [Conversation] 的消息/块/live 计数与长度、
 *   active/lastTerminal 回合（含 conversationId 与 correlation IDs）、块相位与当前长度、
 *   工具意图/参数长度与结果类型、授权请求/渠道/尝试/结果、全部阻塞种类与原因。
 * - 默认脱敏：只输出结构化状态与长度，不输出输入/正文/思考/工具参数与结果全文、
 *   任意异常 detail、权限 target、attempt.detail；不使用 snapshot/对象 toString。
 *   [Reason.code] 只输出已知来源枚举/静态码（见 [KNOWN_REASON_CODES]），其余按长度分类；
 *   标识符先做控制字符/凭据形态过滤。
 * - 显式调试摘要：[debugSummary] 为 true 时追加脱敏摘要。检测到敏感字段（含
 *   Bearer/无标签凭据形态）时**整段丢弃**，不做部分替换；未命中时压缩空白并截断到
 *   [MAX_DEBUG_SUMMARY]。脱敏为尽力而为，无法保证识别一切无标签密钥，故默认关闭。
 * - sink 异常隔离：sink 抛错只跳过该条并继续采集（首次经默认 Logger 报告），
 *   不影响 AI 执行、不取消收集。
 */
object ConversationRuntimeLogger {

    /**
     * 启动独立消费者。
     *
     * @param runtime 只读 snapshot 提供方（本消费者不调用其任何命令）。
     * @param scope 消费者寿命作用域（App 注入，与执行作用域分离）。
     * @param debugSummary 显式开启脱敏调试摘要，默认 false（无持久化开关）。
     * @param sink 日志输出接缝；默认生产 [LoggerSink]，测试注入记录型 sink。
     * @param dispatcher 消费者调度器，与执行调度器显式分离；生产默认 [Dispatchers.IO]，
     *   避免同步 sink 阻塞 AI 所在调度器。测试可注入确定性调度器（如
     *   StandardTestDispatcher/UnconfinedTestDispatcher）控制采集时序；消费者 Job
     *   仍是 [scope] 子 Job，寿命不受影响。
     */
    fun start(
        runtime: ConversationRuntime,
        scope: CoroutineScope,
        debugSummary: Boolean = false,
        sink: ConversationRuntimeLogSink = LoggerSink,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): Job = scope.launch(dispatcher) {
        var sinkFailureReported = false
        var previousShape: String? = null
        runtime.snapshot.collect { snapshot ->
            // 门控前置：关闭时不构造任何记录，避免「格式化完再被 Logger 丢弃」的空转。
            if (!sink.isEnabled()) return@collect
            val lines = try {
                records(snapshot, debugSummary)
            } catch (_: Throwable) {
                // 格式化异常不终止采集：跳过本版本，下一版本继续。
                return@collect
            }
            // 连续量（版本/时间戳/长度/参数）不参与判定：流式期间同一状态否则会重复成行上百次。
            val shape = shapeOf(lines)
            if (shape == previousShape) return@collect
            previousShape = shape
            lines.forEach { record ->
                try {
                    sink.log(record)
                } catch (throwable: Throwable) {
                    // sink 抛错不影响 AI/执行，也不取消收集；仅首次报告，避免错误刷屏。
                    if (!sinkFailureReported) {
                        sinkFailureReported = true
                        runCatching {
                            Logger.w(TAG, "log sink failed type=${throwable::class.java.simpleName}")
                        }
                    }
                }
            }
        }
    }

    // ── 形状（判重） ─────────────────────────────────────────────────────

    /**
     * 记录形状：把连续量掩为 `#` 后的文本，用于判断相邻快照是否有实质变化。
     * 掩掉的只有长度/参数/版本/时间戳/重试延时这些每帧都会变的量；块、工具、
     * 权限的身份与状态均保留，所以形状相同即意味着没有任何结构性变化。
     */
    private fun shapeOf(lines: List<String>): String =
        lines.joinToString("\n") { line ->
            VOLATILE_FIELD.replace(line) { match -> match.groupValues[1] + "=#" }
        }

    /** 注意 `[` `]` 也在排除集内：`attempts=1[a:B,c:D]` 只掩计数，不掩尝试状态。 */
    private val VOLATILE_FIELD = Regex("\\b(v|t|len|argsLen|live|attempts|delay)=[^ ,}\\]\\[]+")

    // ── 字段 → 有界记录 ──────────────────────────────────────────────────

    private fun records(snapshot: ConversationSnapshot, debugSummary: Boolean): List<String> {
        val parts = ArrayList<String>()
        parts += " t=${snapshot.updatedAtMillis}"
        parts += buildString {
            append(" session{sid=").append(safeToken(snapshot.session.runtimeConversationId))
            append(" pid=").append(safeToken(snapshot.session.persistedId))
            append(" op=").append(snapshot.session.operation?.name ?: "-")
            append(" phase=").append(snapshot.session.phase.name)
            append(" reason=").append(reason(snapshot.session.reason))
            append('}')
        }
        parts += conversationText(snapshot.conversation)
        addTurn(parts, " active", snapshot.active)
        addTurn(parts, " last", snapshot.lastTerminal)
        if (debugSummary) {
            debugFragment(snapshot)?.let { parts += " debug=$it" }
        }
        val bodies = pack(parts, MAX_RECORD_BYTES - PREFIX_RESERVE)
        return bodies.mapIndexed { index, body ->
            "v=${snapshot.version} ${index + 1}/${bodies.size}$body"
        }
    }

    /** 同源内容源元数据：只计数与长度，不下钻正文/参数/结果，不重建状态。 */
    private fun conversationText(conversation: Conversation?): String {
        if (conversation == null) return " conv{-}"
        var users = 0
        var assistants = 0
        var toolResults = 0
        var blocks = 0
        var length = 0
        conversation.history.forEach { entry ->
            when (val message = entry.message) {
                is Message.User -> {
                    users++
                    blocks += message.content.size
                    length += message.content.sumOf { contentLength(it) }
                }

                is Message.Assistant -> {
                    assistants++
                    blocks += message.message.content.size
                    length += message.message.content.sumOf { contentLength(it) }
                }

                is Message.ToolResult -> toolResults++
            }
        }
        val live = conversation.live
        return buildString {
            append(" conv{id=").append(safeToken(conversation.id))
            append(" entries=").append(conversation.history.size)
            append(" leaf=").append(safeToken(conversation.leafId))
            append(" roles={u=").append(users)
                .append(",a=").append(assistants)
                .append(",t=").append(toolResults)
                .append('}')
            append(" blocks=").append(blocks)
            append(" len=").append(length)
            append(" live=")
            if (live == null) {
                append('-')
            } else {
                append(live.content.size).append('/')
                    .append(live.content.sumOf { contentLength(it) })
            }
            append('}')
        }
    }

    private fun addTurn(parts: MutableList<String>, label: String, turn: TurnObservation?) {
        if (turn == null) {
            parts += " $label{-}"
            return
        }
        parts += buildString {
            append(' ').append(label).append("{phase=").append(turn.phase.name)
            append(" src=").append(turn.source.name)
            append(" att=").append(turn.attempt)
            append(" key=").append(safeToken(turn.key.conversationId))
                .append('/').append(safeToken(turn.key.turnId)).append('@').append(turn.key.epoch)
            append(" reason=").append(reason(turn.reason))
            append(" blocks=[")
        }
        turn.blocks.forEachIndexed { index, block ->
            parts += buildString {
                if (index > 0) append(',')
                append(block.key.attempt).append(':').append(block.key.messageOrdinal)
                    .append(':').append(block.key.index).append('{')
                append(block.kind.name).append('/').append(block.phase.name)
                append(" len=").append(blockLength(block) ?: -1)
                append('}')
            }
        }
        parts += "] tools=["
        turn.tools.forEachIndexed { index, tool ->
            parts += buildString {
                if (index > 0) append(',')
                append(tool.block.attempt).append(':').append(tool.block.messageOrdinal)
                    .append(':').append(tool.block.index).append('{')
                append(tool.phase.name)
                append(" call=").append(safeToken(tool.callId))
                append(" nameLen=").append(tool.call?.name?.length ?: 0)
                append(" argsLen=").append(tool.call?.argumentsJson?.length ?: 0)
                append(" outcome=").append(tool.outcome?.let { it::class.simpleName } ?: "-")
                append(" reason=").append(reason(tool.reason))
                append('}')
            }
        }
        parts += "] perms=["
        turn.permissions.forEachIndexed { index, permission ->
            parts += (if (index > 0) "," else "") + permissionText(permission)
        }
        parts += "] blockers=["
        turn.blockers.forEachIndexed { index, blocker ->
            parts += buildString {
                if (index > 0) append(',')
                append(blocker.kind.name).append('{')
                append("id=").append(safeToken(blocker.id))
                append(" call=").append(safeToken(blocker.toolCallId))
                append(" req=").append(safeToken(blocker.requestId))
                append(" delay=").append(blocker.retryDelayMs?.toString() ?: "?")
                append(" cause=").append(reason(blocker.reason))
                append('}')
            }
        }
        parts += "]}"
    }

    private fun permissionText(permission: PermissionRequestObservation): String = buildString {
        append("req=").append(safeToken(permission.requestId)).append('{')
        append("kind=").append(permission.kind.name)
        append(" call=").append(safeToken(permission.toolCallId))
        append(" channel=").append(safeToken(permission.channel))
        append(" phase=").append(permission.phase.name)
        append(" attempts=").append(permission.attempts.size).append('[')
        permission.attempts.forEachIndexed { index, attempt ->
            if (index > 0) append(',')
            append(attempt.channel.name).append(':').append(attempt.state.name)
        }
        append(']')
        append(" reason=").append(reason(permission.reason))
        append('}')
    }

    // ── 原因码 ──────────────────────────────────────────────────────────

    /**
     * 只输出**已知**结构化来源的枚举/静态码；来源未知输出 `?origin`，码不在该来源
     * 白名单（含 OKIA `RetryScheduled.reason` 任意文本、Runtime 抛异常 simpleName、
     * 未来新码）输出 `?code(len=N)`，不打印原文。`detail` 一律不在此输出。
     */
    private fun reason(reason: Reason?): String {
        if (reason == null) return "-"
        val known = KNOWN_REASON_CODES[reason.origin] ?: return "?origin"
        val code = if (reason.code in known) reason.code else "?code(len=${reason.code.length})"
        return "$code@${reason.origin}"
    }

    // ── 块长度 ──────────────────────────────────────────────────────────

    /**
     * 块当前长度：Ended 块用 [BlockObservation.content]，流式 Started/Delta 的正文在
     * [BlockObservation.partial] 里，按捕获的块 index/kind 从实际 partial 取同 kind 内容；
     * 形状不匹配或缺证据返回 null（未知），不回退到别的块、不合成文本。
     */
    private fun blockLength(block: BlockObservation): Int? {
        block.content?.let { return contentLength(block.kind, it) }
        val atIndex = block.partial?.content?.getOrNull(block.key.index) ?: return null
        return contentLength(block.kind, atIndex)
    }

    private fun contentLength(kind: BlockKind, content: ContentBlock): Int? = when (kind) {
        BlockKind.Text -> (content as? ContentBlock.Text)?.text?.length
        BlockKind.Thinking -> (content as? ContentBlock.Thinking)?.text?.length
        BlockKind.ToolCall -> (content as? ContentBlock.ToolCall)?.argumentsJson?.length
    }

    private fun contentLength(content: ContentBlock): Int = when (content) {
        is ContentBlock.Text -> content.text.length
        is ContentBlock.Thinking -> content.text.length
        is ContentBlock.ToolCall -> content.argumentsJson.length
        is ContentBlock.Image -> 0
    }

    // ── 显式调试摘要（保守丢弃 + 截断） ──────────────────────────────────

    /**
     * 摘要候选只有最新块文本/工具名与原因 detail。命中敏感字段或无标签凭据形态时返回
     * null（整段丢弃），不做可能泄漏的后缀替换；未命中时压缩空白并截断到
     * [MAX_DEBUG_SUMMARY]。局限：无法识别一切无标签密钥，故调试摘要默认关闭。
     */
    private fun debugFragment(snapshot: ConversationSnapshot): String? {
        val raw = debugPreview(snapshot)
        if (raw.isEmpty()) return null
        if (SENSITIVE_MARKER.containsMatchIn(raw) || SECRET_LIKE.containsMatchIn(raw)) return null
        return raw.replace(WHITESPACE, " ").trim().take(MAX_DEBUG_SUMMARY)
    }

    /** 摘要候选只有最新块文本/工具名与原因 detail；无候选时为空。 */
    private fun debugPreview(snapshot: ConversationSnapshot): String {
        val turn = snapshot.active ?: snapshot.lastTerminal
        val blockText = turn?.blocks?.lastOrNull()?.let { block ->
            when (val content = block.content) {
                is ContentBlock.Text -> content.text
                is ContentBlock.Thinking -> content.text
                is ContentBlock.ToolCall -> content.name
                is ContentBlock.Image -> null
                null -> partialText(block)
            }
        }
        return listOfNotNull(blockText, turn?.reason?.detail).joinToString(" ")
    }

    /** 流式块正文在 partial 中，按块 index/kind 取同 kind 文本；不匹配返回 null。 */
    private fun partialText(block: BlockObservation): String? {
        val content = block.partial?.content?.getOrNull(block.key.index) ?: return null
        return when (block.kind) {
            BlockKind.Text -> (content as? ContentBlock.Text)?.text
            BlockKind.Thinking -> (content as? ContentBlock.Thinking)?.text
            BlockKind.ToolCall -> (content as? ContentBlock.ToolCall)?.name
        }
    }

    // ── 分片（UTF-8 字节界 + 码点边界） ──────────────────────────────────

    /**
     * 按 UTF-8 字节预算把逻辑字段包成记录体：优先在字段边界断开；单个字段超预算时按
     * 码点安全硬切。不丢字段、不截断列表尾部。
     */
    private fun pack(parts: List<String>, budget: Int): List<String> {
        val records = ArrayList<String>()
        val sb = StringBuilder()
        var used = 0
        fun flush() {
            if (sb.isNotEmpty()) {
                records += sb.toString()
                sb.setLength(0)
                used = 0
            }
        }
        parts.forEach { part ->
            val bytes = utf8Bytes(part)
            if (bytes <= budget) {
                if (used > 0 && used + bytes > budget) flush()
                sb.append(part)
                used += bytes
            } else {
                flush()
                records += splitByCodePoint(part, budget)
            }
        }
        flush()
        return records
    }

    /** 单串按码点切成每段 ≤ [maxBytes] UTF-8 字节，绝不切开代理对。 */
    private fun splitByCodePoint(text: String, maxBytes: Int): List<String> {
        val pieces = ArrayList<String>()
        val sb = StringBuilder()
        var used = 0
        var index = 0
        while (index < text.length) {
            val codePoint = Character.codePointAt(text, index)
            val charCount = Character.charCount(codePoint)
            val bytes = utf8Bytes(codePoint)
            if (used + bytes > maxBytes && sb.isNotEmpty()) {
                pieces += sb.toString()
                sb.setLength(0)
                used = 0
            }
            sb.append(text, index, index + charCount)
            used += bytes
            index += charCount
        }
        if (sb.isNotEmpty()) pieces += sb.toString()
        return pieces
    }

    private fun utf8Bytes(text: String): Int {
        var bytes = 0
        var index = 0
        while (index < text.length) {
            val codePoint = Character.codePointAt(text, index)
            bytes += utf8Bytes(codePoint)
            index += Character.charCount(codePoint)
        }
        return bytes
    }

    private fun utf8Bytes(codePoint: Int): Int = when {
        codePoint < 0x80 -> 1
        codePoint < 0x800 -> 2
        codePoint < 0x10000 -> 3
        else -> 4
    }

    // ── 安全化 ──────────────────────────────────────────────────────────

    /**
     * 标识符/短元数据安全化：空或缺失 = 未知 `?`；含控制字符、超长或疑似凭据形态时降级为
     * 分类占位，不原样输出。用于 ID/渠道/原因来源等自由形态字段。
     */
    private fun safeToken(raw: String?, limit: Int = 96): String {
        if (raw.isNullOrEmpty()) return "?"
        if (CONTROL_CHARS.containsMatchIn(raw)) return "?ctrl(len=${raw.length})"
        if (raw.length > limit) return "?long(len=${raw.length})"
        if (SECRET_LIKE.containsMatchIn(raw)) return "?redacted"
        return raw
    }

    private val WHITESPACE = Regex("\\s+")
    private val CONTROL_CHARS = Regex("[\\p{Cntrl}]")

    /**
     * 已知结构化原因码白名单：来源 → 该来源允许打印的码集合。
     * 覆盖 ConversationStateReducer/ConversationExecutor/ConversationPermissionObserver、
     * OKIA 的 LLMErrorCode/StopCause、permission-manager 的 PermissionState，以及
     * ToolPermissionCoordinator/ScreenControlConsent 的静态码。OKIA RetryScheduled.reason
     * 与 Runtime 抛异常 simpleName 不在此列，按未知分类。
     */
    private val KNOWN_REASON_CODES: Map<String, Set<String>> = mapOf(
        "EXECUTOR" to setOf(
            "STOP_REQUEST", "CANCEL_REQUEST", "CANCELLED", "EXECUTION_FAILED",
            "CAPABILITY_PREPARATION",
            "OwnerCleared", "HostTaskCancelled", "BinderDied", "ServiceDestroyed", "SessionReset",
        ),
        "OKIA" to setOf(
            // LLMErrorCode
            "Auth", "Quota", "RateLimit", "Overloaded", "ContextOverflow", "Transport",
            "Parse", "HookFailed", "ToolExecutionFailed", "RetryExhausted",
            // StopCause
            "UserStop", "External",
            // 固定字面量
            "IDLE_TIMEOUT",
        ),
        "PermissionEngine" to setOf(
            "GRANTED", "DENIED_BY_USER", "UNAVAILABLE", "FAILED", "UNKNOWN",
            "HANDLER_THREW", "CANCELLED",
        ),
        "RUNTIME" to setOf("OPERATION_BACKEND_MISSING", "CANCELLED"),
        "NOT_PROVIDED" to setOf("UNKNOWN"),
        "ToolPermissionCoordinator" to setOf(
            "CANCELLED", "FAILED", "DENIED_BY_USER", "UNAVAILABLE",
        ),
        "ScreenControlConsent" to setOf(
            "BACKGROUND_REFUSED", "DENIED_BY_USER", "CANCELLED", "FAILED",
        ),
    )

    /**
     * 敏感字段标记：命中即整段丢弃调试摘要（不做部分替换，避免转义引号导致的后缀泄漏）。
     * 键可带引号；值形态不参与匹配，故值里的转义引号不会绕过检测。
     */
    private val SENSITIVE_MARKER = Regex(
        "(?i)([\"']?\\b(?:api[_-]?key|access[_-]?token|refresh[_-]?token|token|password|passwd|secret|authorization|auth)\\b[\"']?\\s*[:=]" +
            "|\\bBearer\\s+\\S{4,})",
    )

    /** 无标签凭据形态（用于整段丢弃与 safeToken 降级）。 */
    private val SECRET_LIKE = Regex(
        "(?i)(sk-[A-Za-z0-9_\\-]{8,}|Bearer\\s+[A-Za-z0-9._~+/=\\-]{8,}|eyJ[A-Za-z0-9_\\-]{10,})",
    )

    private const val MAX_DEBUG_SUMMARY = 120

    /** Logcat 单条 payload 上限约 4KB；保守留出 1KB 余量，并预留分片头空间。 */
    private const val MAX_RECORD_BYTES = 3000
    private const val PREFIX_RESERVE = 48
}

private const val TAG = "niki914_nexus_ConversationRuntime"
