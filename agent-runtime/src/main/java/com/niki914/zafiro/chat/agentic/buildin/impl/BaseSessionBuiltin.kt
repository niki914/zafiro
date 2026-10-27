// githooks:ignore-file file-token-limit
package com.niki914.zafiro.chat.agentic.buildin.impl

import com.niki914.logging.Logger
import com.niki914.zafiro.chat.agentic.ToolExecutionPreflight
import com.niki914.zafiro.chat.agentic.buildin.BuiltinTool
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolResult
import com.niki914.zafiro.chat.agentic.buildin.RawJsonBuiltinTool
import com.niki914.zafiro.chat.agentic.shell.SessionCommandState
import com.niki914.zafiro.chat.agentic.shell.SessionRegistry
import com.niki914.zafiro.chat.agentic.shell.TerminalCloseOutcome
import com.niki914.zafiro.chat.agentic.shell.TerminalInteractiveReadOutcome
import com.niki914.zafiro.chat.agentic.shell.TerminalInteractiveWriteOutcome
import com.niki914.zafiro.chat.agentic.shell.TerminalReadMode
import com.niki914.zafiro.chat.agentic.shell.TerminalSessionPool
import com.niki914.zafiro.chat.agentic.shell.TerminalToolResponse
import com.niki914.zafiro.util.ToolOutputTruncator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.UUID

/**
 * 会话式终端工具的统一抽象基类。
 * 负责别名管理 (Session Registry)、Sentinel 退出码解析、同步超时优雅降级、异步后台监听、通用 Action 分发以及回执自证。
 */
abstract class BaseSessionBuiltin(
    protected val preflight: ToolExecutionPreflight = ToolExecutionPreflight(),
) : BuiltinTool(), RawJsonBuiltinTool {

    val sessionRegistry: SessionRegistry get() = SessionRegistry

    /**
     * 会话生死以池为准：登记只记「开过」。SSH 掉线 / 本地会话被回收后，
     * 登记命中只是僵尸，寻址时必须回查，否则 alias 永远指向死会话。
     * 测试的假会话不进池，子类（含测试 fake）可覆写。
     */
    protected open fun isSessionAlive(sessionId: String): Boolean =
        TerminalSessionPool.isSessionAlive(sessionId)

    /**
     * Ctrl-C (\u0003) 是否是真实信号。只有 PTY 会话（SSH）成立；
     * 本地 shell 走 libsu 管道没有 PTY，\u0003 只是输入字节，还会污染下一条命令。
     */
    protected open val supportsCtrlCAbort: Boolean get() = false

    /** 别名域，本地 shell 与 ssh 各自一套命名空间，避免别名跨工具解析到对方的会话。 */
    protected open val sessionScope: String get() = SessionRegistry.SCOPE_LOCAL

    /** 卡死命令的脱困指引，按是否有 PTY 分流（写入超时/忙拒绝/后台受理三处共用）。 */
    protected val abandonHint: String
        get() = if (supportsCtrlCAbort) {
            "abandon it with action=\"write\" and text \"\\u0003\" (Ctrl-C) — that frees this session for the next command; " +
                    "if you only need a shell right now, leave this session alone and run your next command in a new session with a different alias."
        } else {
            "close the session with action='close' and start a new one with a different alias."
        }

    /** 回执截断时的全量导出目录；null 时取沙箱默认目录（单测注入临时目录）。 */
    internal var exportDirOverride: File? = null

    override val defaultEnabled: Boolean = true

    override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
        return BuiltinToolResult.failure(
            code = "RAW_JSON_ONLY",
            message = "$name accepts raw JSON requests only.",
            hint = """Example: {"command":"pwd"} or {"action":"list"}""",
        )
    }

    override suspend fun invokeRawJson(request: BuiltinToolRequest): String {
        return try {
            val args = parseArguments(request.argumentsJson)
            dispatchRequest(args)
        } catch (error: CancellationException) {
            throw error
        } catch (error: SerializationException) {
            TerminalToolResponse.invalidRequest(error.message ?: "Invalid JSON arguments.")
        } catch (error: IllegalArgumentException) {
            TerminalToolResponse.invalidRequest(error.message ?: "Invalid request.")
        } catch (error: Throwable) {
            Logger.e(LOG_TAG, "Unexpected error in $name", error)
            TerminalToolResponse.internalError(error)
        }
    }

    /**
     * 子类实现参数解析逻辑。
     */
    protected abstract fun parseArguments(argumentsJson: String): BaseSessionArgs

    /**
     * 派发请求。当存在 action 时优先路由至动作分发；否则路由至命令分发。
     */
    protected open suspend fun dispatchRequest(args: BaseSessionArgs): String {
        return when {
            args.action != null -> handleAction(args)
            args.command != null -> handleCommand(args)
            else -> TerminalToolResponse.invalidRequest(
                "Either 'command' or 'action' is required."
            )
        }
    }

    /**
     * 当请求未提供 session 与 alias，且子类未重写 [resolveSessionTarget] 时调用。
     * 默认返回 null。
     */
    protected open suspend fun onDefaultSession(): String? = null

    /**
     * 会话解析结果，封装目标 session_id 或格式化好的错误回执。
     */
    sealed interface SessionTarget {
        data class Resolved(val sessionId: String) : SessionTarget
        data class Error(val responseJson: String) : SessionTarget
    }

    /**
     * 解析目标 session_id。
     * 优先通过 SessionRegistry 查找别名或 ID，未传时尝试落入 onDefaultSession。
     */
    protected open suspend fun resolveTargetSessionId(explicitTarget: String?): String? {
        val trimmed = explicitTarget?.trim()?.takeIf(String::isNotBlank)
        if (trimmed != null) {
            return sessionRegistry.resolveSessionId(trimmed, sessionScope) ?: trimmed
        }
        return onDefaultSession()
    }

    /**
     * 根据请求参数解析目标 session_id。
     */
    protected open suspend fun resolveTargetSessionId(args: BaseSessionArgs): String? {
        val target = args.session ?: args.alias
        return resolveTargetSessionId(target)
    }

    /**
     * 根据请求参数解析目标 session 句柄。
     * 子类（如 ShellBuiltin, SshBuiltin）可重写此方法以定制会话寻址、按需新建、凭据校验及错误回执。
     */
    protected open suspend fun resolveSessionTarget(args: BaseSessionArgs): SessionTarget {
        val target = args.session ?: args.alias
        val resolved = resolveTargetSessionId(target)
        return if (resolved != null) {
            SessionTarget.Resolved(resolved)
        } else {
            val errorMsg = if (args.action != null) {
                "Field 'session' is required for action='${args.action.name.lowercase()}'."
            } else {
                "Field 'session' is required (or specify connection parameters)."
            }
            SessionTarget.Error(TerminalToolResponse.invalidRequest(errorMsg))
        }
    }

    // ── 通用命令执行引擎 (Sentinel + 同步等待 + 超时降级 + 异步后台) ───────────

    protected open suspend fun handleCommand(args: BaseSessionArgs): String {
        val sessionId = when (val target = resolveSessionTarget(args)) {
            is SessionTarget.Resolved -> target.sessionId
            is SessionTarget.Error -> return target.responseJson
        }
        val command = args.command?.takeIf(String::isNotBlank)
            ?: return TerminalToolResponse.invalidRequest("Field 'command' must not be blank.")
        val decision = preflight.evaluate(
            command,
            toolName = name,
            identity = actualSessionIdentity(sessionId),
        )
        if (!decision.allowed) {
            return TerminalToolResponse.policyBlocked(decision)
        }
        preflightPathAccess(command, args)
        val timeoutSec = (args.timeout ?: DEFAULT_TIMEOUT_SEC).coerceAtLeast(1)

        return executeSentinelCommand(
            sessionId = sessionId,
            command = command,
            timeoutSec = timeoutSec,
            background = args.background,
        )
    }

    /**
     * 会话固化下来的执行身份（`user` / `root` / `shizuku`）；未登记的会话返回 null。
     * 安全前检必须用它而非入参：入参只用于开辟新会话，已有会话的身份在建时已定。
     */
    protected fun actualSessionIdentity(sessionId: String): String? =
        sessionRegistry.findById(sessionId)?.metadata?.get(SessionRegistry.META_IDENTITY)

    /**
     * 命令执行前的路径权限前检。远端 SSH 命令跑在别的机器上，本地沙箱权限帮不上忙，
     * 因此默认不申请，只有本地 shell 覆写它。
     */
    protected open suspend fun preflightPathAccess(command: String, args: BaseSessionArgs) = Unit

    protected open suspend fun executeSentinelCommand(
        sessionId: String,
        command: String,
        timeoutSec: Long,
        background: Boolean,
    ): String {
        val meta = sessionRegistry.findById(sessionId)
        // 同一 PTY 里塞第二条命令会让它排在未完成的命令后面执行，两边的输出都会错位丢失
        if (meta?.activeCommand?.status == "running") {
            return TerminalToolResponse.sessionBusyWithPendingCommand(sessionId, abandonHint)
        }
        val token = randomToken()
        val payload = SessionSentinel.buildPayload(command, token)

        val initialOffset = readRawOutput(sessionId)?.length ?: 0

        val commandState = SessionCommandState(
            command = command,
            token = token,
            startTimeMs = System.currentTimeMillis(),
            initialOffset = initialOffset,
            status = "running",
        )
        sessionRegistry.updateCommandState(sessionId, commandState)

        val sent = sendInput(sessionId, payload)
        if (!sent) {
            commandState.status = "failed"
            return TerminalToolResponse.commandError(
                code = "SEND_FAILED",
                message = "Failed to send command to session '$sessionId'.",
            )
        }

        if (background) {
            return formatBackgroundAccepted(
                sessionId = sessionId,
                alias = meta?.alias,
            )
        }

        // 同步等待 Sentinel
        val parsed = awaitSentinel(
            sessionId,
            token,
            timeoutMs = timeoutSec * 1000L,
            initialOffset = initialOffset
        )
        return if (parsed != null) {
            commandState.status = if (parsed.exitCode == 0) "exited" else "failed"
            commandState.exitCode = parsed.exitCode
            commandState.cleanOutput = parsed.cleanOutput
            commandState.endTimestampMs = System.currentTimeMillis()
            // 全量输出已随本条回执交付，后续重复 read 只回 already_read
            commandState.outputDelivered = true
            formatCommandResult(
                sessionId = sessionId,
                alias = meta?.alias,
                output = parsed.cleanOutput,
                exitCode = parsed.exitCode,
            )
        } else if (!isSessionAlive(sessionId)) {
            // 连接已断：写入会「成功」但哨兵永远不回，等满超时只能拿到空输出。
            // 立刻给出可行动的错误，并清掉卡死的在途状态。
            commandState.status = "failed"
            sessionRegistry.updateCommandState(sessionId, null)
            TerminalToolResponse.sessionGone(sessionId)
        } else {
            // 超时到达：优雅降级为后台运行，不杀会话与进程
            commandState.status = "running"
            commandState.timedOut = true
            val rawFull = readRawOutput(sessionId)
            val partialOutput = if (rawFull != null && rawFull.length >= initialOffset) {
                rawFull.substring(initialOffset)
            } else rawFull.orEmpty()
            formatCommandTimedOut(
                sessionId = sessionId,
                alias = meta?.alias,
                output = partialOutput,
                timeoutSec = timeoutSec,
            )
        }
    }

    protected open suspend fun awaitSentinel(
        sessionId: String,
        token: String,
        timeoutMs: Long,
        initialOffset: Int = 0,
    ): SessionSentinel.Parsed? {
        val deadline = System.currentTimeMillis() + timeoutMs
        val echoCommand = sessionRegistry.getCommandState(sessionId)?.command
        var lastLength = -1
        var deadQuietTicks = 0
        while (System.currentTimeMillis() < deadline) {
            val rawFull = readRawOutput(sessionId)
            if (rawFull != null) {
                val parsed = SessionSentinel.parseOutput(
                    rawFull.windowFrom(initialOffset),
                    token,
                    echoCommand = echoCommand,
                )
                if (parsed != null) return parsed
            }
            // 会话内的 exit / 自杀命令会先打标记再退出，而标记可能还在采集管道里往外走。
            // 所以判死不能一拍定论：等缓冲连续两拍没有新数据（尾部已收完）才认掉线——
            // 真断连时缓冲本就没有数据，代价也就两拍，仍比等满超时快得多。
            if (!isSessionAlive(sessionId)) {
                val length = rawFull?.length ?: -1
                deadQuietTicks = if (length == lastLength) deadQuietTicks + 1 else 0
                lastLength = length
                if (deadQuietTicks >= DEAD_QUIET_TICKS) return null
            }
            delay(POLL_INTERVAL_MS)
        }
        return lastParse(sessionId, token, initialOffset, echoCommand)
    }

    private suspend fun lastParse(
        sessionId: String,
        token: String,
        initialOffset: Int,
        echoCommand: String?,
    ): SessionSentinel.Parsed? {
        return readRawOutput(sessionId)?.let {
            SessionSentinel.parseOutput(
                it.windowFrom(initialOffset),
                token,
                echoCommand = echoCommand,
            )
        }
    }

    protected open suspend fun sendInput(sessionId: String, text: String): Boolean {
        return when (TerminalSessionPool.writeInteractive(sessionId, text)) {
            is TerminalInteractiveWriteOutcome.Accepted -> true
            else -> false
        }
    }

    protected open suspend fun readRawOutput(sessionId: String): String? {
        val outcome = TerminalSessionPool.readInteractive(
            session = sessionId,
            mode = TerminalReadMode.SNAPSHOT,
            maxBytes = Int.MAX_VALUE,
        )
        // 这里保持原样不清洗 NUL：偏移量(initialOffset / lastReadOffset)与 sentinel 解析都
        // 建立在这个原始视图上，NUL 既不影响标记匹配、也不影响偏移自洽；清洗统一放到回执出口。
        return if (outcome is TerminalInteractiveReadOutcome.Success) {
            outcome.text
        } else null
    }

    /**
     * 终端流里夹带的 NUL 填充对模型没有意义，只烧 token。
     */
    protected fun sanitizeTerminalText(text: String): String {
        if (text.isEmpty()) return text
        val withoutNul = if ('\u0000' in text) text.filter { it != '\u0000' } else text
        // 哨兵管线自己写进去的行（payload 回显、标记行）对模型没有任何信息量。交互式 read 走的是
        // 原始流、不经过 parseOutput，抹不掉就只能在这里兜
        val needsPlumbingFilter = "__sess_status" in withoutNul || "\$__t" in withoutNul ||
                SessionSentinel.MARKER_PREFIX in withoutNul
        if (!needsPlumbingFilter) return withoutNul
        return withoutNul.split('\n')
            .filterNot(::isSentinelPlumbingLine)
            .joinToString("\n")
    }

    private fun isSentinelPlumbingLine(line: String): Boolean {
        if ("__sess_status" in line || "\$__t" in line) return true
        return SessionSentinel.MARKER_PREFIX in line
    }

    /**
     * 回执里的终端文本统一出口：剔 NUL，再超限截断并把全量落盘。
     */
    protected fun prepareReceiptOutput(text: String): String {
        val sanitized = sanitizeTerminalText(text)
        if (sanitized.isEmpty()) return sanitized
        return ToolOutputTruncator.filterForAgent(
            fullContent = sanitized,
            exportDir = exportDirOverride ?: ToolOutputTruncator.defaultExportDir(),
        )
    }

    /** 增量窗口：从指定偏移取到末尾；偏移越界时退回全文。 */
    protected fun String.windowFrom(offset: Int): String =
        if (length >= offset) substring(offset) else this

    // ── 通用动作分发 ────────────────────────────────────────────────────────

    protected open suspend fun handleAction(args: BaseSessionArgs): String {
        return when (args.action) {
            SessionAction.CONNECT -> TerminalToolResponse.invalidRequest("Action 'connect' is not supported by $name.")
            SessionAction.LIST -> handleList()
            SessionAction.READ -> handleRead(args)
            SessionAction.SUBMIT -> handleSubmit(args)
            SessionAction.WRITE -> handleWrite(args)
            SessionAction.CLOSE -> handleClose(args)
            null -> TerminalToolResponse.invalidRequest("Field 'action' is required.")
        }
    }

    protected open fun handleList(): String {
        // 只报本域、且真的还活着的会话：跨域句柄会诱使模型把它交给另一个工具执行，
        // 已断连的死会话列出来则是谎报健康（模型会拿它去发命令，只能拿到 gone）
        val sessions = sessionRegistry.all()
            .filter { it.scope == sessionScope && isSessionAlive(it.sessionId) }
        val items = sessions.map { entry ->
            val payload = linkedMapOf<String, JsonElement>(
                "session_id" to JsonPrimitive(entry.sessionId),
                "created_at" to JsonPrimitive(entry.createdAt),
            )
            entry.alias?.let { payload["alias"] = JsonPrimitive(it) }
            if (entry.metadata.isNotEmpty()) {
                payload["metadata"] =
                    JsonObject(entry.metadata.mapValues { JsonPrimitive(it.value) })
            }
            JsonObject(payload)
        }
        return JsonObject(
            mapOf(
                "action" to JsonPrimitive("list"),
                "total" to JsonPrimitive(sessions.size),
                "sessions" to JsonArray(items),
            )
        ).toString()
    }

    protected open suspend fun handleRead(args: BaseSessionArgs): String {
        val sessionId = when (val target = resolveSessionTarget(args)) {
            is SessionTarget.Resolved -> target.sessionId
            is SessionTarget.Error -> return target.responseJson
        }
        val meta = sessionRegistry.findById(sessionId)
        val activeCommand = meta?.activeCommand

        // 若会话通过 Sentinel 下发了命令，以 Sentinel 状态机检测为准
        if (activeCommand != null) {
            val rawFull = readRawOutput(sessionId).orEmpty()

            if (activeCommand.status == "running" && rawFull.isNotEmpty()) {
                val parsed = SessionSentinel.parseOutput(
                    rawFull.windowFrom(activeCommand.initialOffset),
                    activeCommand.token,
                    echoCommand = activeCommand.command,
                )
                if (parsed != null) {
                    activeCommand.status = if (parsed.exitCode == 0) "exited" else "failed"
                    activeCommand.exitCode = parsed.exitCode
                    activeCommand.cleanOutput = parsed.cleanOutput
                    activeCommand.endTimestampMs = System.currentTimeMillis()
                }
            }
            val elapsed = (
                    (activeCommand.endTimestampMs
                        ?: System.currentTimeMillis()) - activeCommand.startTimeMs
                    ) / 1000L
            return if (activeCommand.status != "running") {
                // 已退出的命令全量输出只交付一次；再次 read 回 already_read，上下文不反复膨胀
                val alreadyRead = activeCommand.outputDelivered
                activeCommand.outputDelivered = true
                formatReadResult(
                    sessionId = sessionId,
                    status = activeCommand.status,
                    output = if (alreadyRead) "" else activeCommand.cleanOutput,
                    exitCode = activeCommand.exitCode,
                    elapsedSeconds = elapsed,
                    extra = if (alreadyRead) mapOf("already_read" to JsonPrimitive(true)) else emptyMap(),
                )
            } else {
                // 只给上次读之后的新增部分，避免反复把同一段输出重新塞进上下文。
                // 下界要跟着实际长度收：缓冲被重建时 rawFull 可能短于 initialOffset，
                // 区间倒置会让 coerceIn 直接抛异常
                val start = activeCommand.lastReadOffset.coerceIn(
                    minOf(activeCommand.initialOffset, rawFull.length),
                    rawFull.length,
                )
                activeCommand.lastReadOffset = rawFull.length
                formatReadResult(
                    sessionId = sessionId,
                    status = "running",
                    output = rawFull.substring(start),
                    exitCode = null,
                    elapsedSeconds = elapsed,
                )
            }
        }

        // 兜底：直接从 TerminalSessionPool 中读交互式输出
        return when (
            val interactiveOutcome = TerminalSessionPool.readInteractive(
                session = sessionId,
                mode = TerminalReadMode.DELTA,
                maxBytes = Int.MAX_VALUE,
            )
        ) {
            is TerminalInteractiveReadOutcome.Success -> formatReadResult(
                sessionId = sessionId,
                status = "running",
                output = interactiveOutcome.text,
                exitCode = null,
                elapsedSeconds = 0L,
            )

            is TerminalInteractiveReadOutcome.SessionNotFound -> TerminalToolResponse.sessionNotFound(
                interactiveOutcome.session
            )

            is TerminalInteractiveReadOutcome.NotInteractive -> TerminalToolResponse.invalidRequest(
                "Session '$sessionId' is not an interactive terminal."
            )
        }
    }

    protected open suspend fun handleSubmit(args: BaseSessionArgs): String {
        val sessionId = when (val target = resolveSessionTarget(args)) {
            is SessionTarget.Resolved -> target.sessionId
            is SessionTarget.Error -> return target.responseJson
        }
        val text = args.text
            ?: return TerminalToolResponse.invalidRequest("Field 'text' is required for submit.")
        // submit 会追换行立即执行，与 command 同等受执行规则约束（\u0003 永不拦）
        val decision = preflight.evaluate(
            text,
            toolName = name,
            identity = actualSessionIdentity(sessionId),
        )
        if (!decision.allowed) {
            return TerminalToolResponse.policyBlocked(decision)
        }
        preflightPathAccess(text, args)
        val meta = sessionRegistry.findById(sessionId)
        if (meta?.activeCommand?.status != "running") {
            sessionRegistry.updateCommandState(sessionId, null)
        }
        return writeInteractivePayload(sessionId, "$text\n")
    }

    protected open suspend fun handleWrite(args: BaseSessionArgs): String {
        val sessionId = when (val target = resolveSessionTarget(args)) {
            is SessionTarget.Resolved -> target.sessionId
            is SessionTarget.Error -> return target.responseJson
        }
        val text = args.text
            ?: return TerminalToolResponse.invalidRequest("Field 'text' is required for write.")
        // 含换行的 write 等同于让 shell 执行脚本，同样过前检；纯交互片段与 Ctrl-C 不拦
        if (ABORT_SEQUENCE !in text && '\n' in text) {
            val decision = preflight.evaluate(
                text,
                toolName = name,
                identity = actualSessionIdentity(sessionId),
            )
            if (!decision.allowed) {
                return TerminalToolResponse.policyBlocked(decision)
            }
            preflightPathAccess(text, args)
        }
        val pending = sessionRegistry.findById(sessionId)?.activeCommand
        if (pending == null || pending.status != "running") {
            sessionRegistry.updateCommandState(sessionId, null)
        } else if (ABORT_SEQUENCE in text) {
            // 非 PTY 会话里 \u0003 只是输入字节：杀不掉进程，还会污染下一条命令行，
            // 所以这里拒绝作废，让模型走 close + 新 alias（此时下一条命令会排在旧进程后面白等超时）
            if (!supportsCtrlCAbort) {
                return TerminalToolResponse.invalidRequest(
                    "Session '$sessionId' has no PTY: \"\\u0003\" is not an interrupt here. " +
                            "Close it with action='close' and start a new session with a different alias."
                )
            }
            // PTY 上 Ctrl-C 会连带冲掉输入队列里那行 sentinel 标记（NOFLSH 未置位 → 输入队列被 flush），
            // 标记不会回来，状态就永远卡在 running。模型显式发 Ctrl-C 即宣布这条命令作废，
            // 直接放行后续命令。残留进程若还活着，其输出只会混进下一次读的文本，
            // 不会污染后续命令的 exit code（每次命令用各自的 token 解析）。
            sessionRegistry.updateCommandState(sessionId, null)
        }
        return writeInteractivePayload(sessionId, text)
    }

    protected open suspend fun handleClose(args: BaseSessionArgs): String {
        val sessionId = when (val target = resolveSessionTarget(args)) {
            is SessionTarget.Resolved -> target.sessionId
            is SessionTarget.Error -> return target.responseJson
        }
        sessionRegistry.unregister(sessionId)
        return when (val outcome = TerminalSessionPool.close(sessionId)) {
            TerminalCloseOutcome.Closed -> JsonObject(
                mapOf(
                    "session_id" to JsonPrimitive(sessionId),
                    "closed" to JsonPrimitive(true),
                )
            ).toString()

            is TerminalCloseOutcome.UnexpectedError -> TerminalToolResponse.internalError(outcome.throwable)
        }
    }

    private suspend fun writeInteractivePayload(sessionId: String, payload: String): String {
        return when (val outcome = TerminalSessionPool.writeInteractive(sessionId, payload)) {
            is TerminalInteractiveWriteOutcome.Accepted -> formatWriteResult(
                sessionId = sessionId,
                bytesWritten = outcome.bytesWritten,
            )

            is TerminalInteractiveWriteOutcome.SessionNotFound -> TerminalToolResponse.sessionNotFound(
                outcome.session
            )

            is TerminalInteractiveWriteOutcome.NotInteractive -> TerminalToolResponse.invalidRequest(
                "Session '${outcome.session}' is not an interactive terminal."
            )

            is TerminalInteractiveWriteOutcome.Busy -> TerminalToolResponse.sessionBusy(
                outcome.session,
                asyncId = null,
            )

            is TerminalInteractiveWriteOutcome.UnexpectedError -> TerminalToolResponse.internalError(
                outcome.throwable
            )
        }
    }

    // ── 回执格式化辅助 ──────────────────────────────────────────────────────

    protected fun formatReadResult(
        sessionId: String,
        status: String,
        output: String,
        exitCode: Int?,
        elapsedSeconds: Long,
        extra: Map<String, JsonElement> = emptyMap(),
    ): String {
        val meta = sessionRegistry.findById(sessionId)
        val payload = linkedMapOf<String, JsonElement>(
            "session_id" to JsonPrimitive(sessionId),
            "status" to JsonPrimitive(status),
            "output" to JsonPrimitive(prepareReceiptOutput(output)),
            "elapsed_seconds" to JsonPrimitive(elapsedSeconds),
        )
        meta?.alias?.let { payload["alias"] = JsonPrimitive(it) }
        meta?.metadata?.get("identity")?.let { payload["identity"] = JsonPrimitive(it) }
        exitCode?.let { payload["exit_code"] = JsonPrimitive(it) }
        payload.putAll(extra)
        return JsonObject(payload).toString()
    }

    protected fun formatWriteResult(sessionId: String, bytesWritten: Int): String {
        val meta = sessionRegistry.findById(sessionId)
        val payload = linkedMapOf<String, JsonElement>(
            "session_id" to JsonPrimitive(sessionId),
            "bytes_written" to JsonPrimitive(bytesWritten),
        )
        meta?.alias?.let { payload["alias"] = JsonPrimitive(it) }
        meta?.metadata?.get("identity")?.let { payload["identity"] = JsonPrimitive(it) }
        return JsonObject(payload).toString()
    }

    protected fun formatCommandResult(
        sessionId: String,
        alias: String?,
        output: String,
        exitCode: Int,
        extra: Map<String, JsonElement> = emptyMap(),
    ): String {
        val meta = sessionRegistry.findById(sessionId)
        val payload = linkedMapOf<String, JsonElement>(
            "session_id" to JsonPrimitive(sessionId),
            "output" to JsonPrimitive(prepareReceiptOutput(output)),
            "exit_code" to JsonPrimitive(exitCode),
            "status" to JsonPrimitive(if (exitCode == 0) "exited" else "failed"),
        )
        alias?.let { payload["alias"] = JsonPrimitive(it) }
        meta?.metadata?.get("identity")?.let { payload["identity"] = JsonPrimitive(it) }
        payload.putAll(extra)
        return JsonObject(payload).toString()
    }

    protected fun formatBackgroundAccepted(
        sessionId: String,
        alias: String?,
    ): String {
        val meta = sessionRegistry.findById(sessionId)
        val payload = linkedMapOf<String, JsonElement>(
            "session_id" to JsonPrimitive(sessionId),
            "background" to JsonPrimitive(true),
            "status" to JsonPrimitive("running"),
            "output" to JsonPrimitive(
                "Background command started. It runs with status='running'; collect its output later " +
                        "with action='read' instead of polling in a tight loop. If it is stuck, $abandonHint " +
                        "A session with a command still in flight rejects new commands."
            ),
        )
        alias?.let { payload["alias"] = JsonPrimitive(it) }
        meta?.metadata?.get("identity")?.let { payload["identity"] = JsonPrimitive(it) }
        return JsonObject(payload).toString()
    }

    protected fun formatCommandTimedOut(
        sessionId: String,
        alias: String?,
        output: String,
        timeoutSec: Long,
    ): String {
        val meta = sessionRegistry.findById(sessionId)
        val payload = linkedMapOf<String, JsonElement>(
            "session_id" to JsonPrimitive(sessionId),
            "status" to JsonPrimitive("running"),
            "timed_out" to JsonPrimitive(true),
            "output" to JsonPrimitive(prepareReceiptOutput(output)),
            "message" to JsonPrimitive(
                "Command did not finish within ${timeoutSec}s. It keeps running in the background with " +
                        "status='running'; collect its output later with action='read' instead of polling in a tight loop. " +
                        "If it is stuck, $abandonHint"
            ),
        )
        alias?.let { payload["alias"] = JsonPrimitive(it) }
        meta?.metadata?.get("identity")?.let { payload["identity"] = JsonPrimitive(it) }
        return JsonObject(payload).toString()
    }

    private fun randomToken(): String {
        return UUID.randomUUID().toString().replace("-", "").take(8)
    }

    // ── 内部类型与对象（SessionRegistry / SessionMeta / SessionCommandState 已上移至 shell 包，进程级单例）──

    object SessionSentinel {
        const val MARKER_PREFIX = "__SESSION_EXIT_"
        const val MARKER_SUFFIX = "__"

        fun buildPayload(command: String, token: String): String {
            val normalizedCommand = if (command.endsWith('\n')) command else "$command\n"
            return buildString {
                append(normalizedCommand)
                // 标记由 $__t 与 token 在 shell 内拼装：输入文本里不出现完整标记，
                // tty 回显因此伪造不出真标记，parseOutput 直接按真标记切分。
                append("__sess_status=$?; __t=\"")
                append(MARKER_PREFIX)
                append("\"; printf '\\n%s%s:%s__\\n' \"\$__t\" \"")
                append(token)
                append("\" \"\$__sess_status\"\n")
            }
        }

        data class Parsed(
            val exitCode: Int,
            val cleanOutput: String,
        )

        fun parseOutput(rawOutput: String, token: String, echoCommand: String? = null): Parsed? {
            val tokenPrefix = "$MARKER_PREFIX$token:"
            val prefixIndex = rawOutput.lastIndexOf(tokenPrefix)
            if (prefixIndex < 0) return null
            val suffixIndex = rawOutput.indexOf(MARKER_SUFFIX, prefixIndex + tokenPrefix.length)
            if (suffixIndex < 0) return null

            val exitCodeStr = rawOutput.substring(prefixIndex + tokenPrefix.length, suffixIndex)
            val exitCode = exitCodeStr.trim().toIntOrNull() ?: return null

            val cleanBefore = stripEcho(
                rawOutput.substring(0, prefixIndex).trimEnd('\r', '\n'),
                token,
                echoCommand,
            )

            val cleanAfter =
                rawOutput.substring(suffixIndex + MARKER_SUFFIX.length).trimStart('\r', '\n')
            val clean = when {
                cleanBefore.isEmpty() -> cleanAfter
                cleanAfter.isEmpty() -> cleanBefore
                else -> "$cleanBefore\n$cleanAfter"
            }

            return Parsed(exitCode = exitCode, cleanOutput = clean)
        }

        /**
         * 回执去噪：tty 会把输入 payload 原样回显。只处理首尾，不碰中间，真实输出零风险：
         * ① 首行的命令回显（命令文本已知，前后留 24 字符给提示符）；
         * ② 哨兵行的回显残片 —— 含我们的私有变量名 / 裸 token / 纯引号碎片（如 `~ $ "`）；
         * 裸提示符行（`~ $ `）保留，它是「shell 还活着」的唯一视觉信号。
         */
        private fun stripEcho(cleanBefore: String, token: String, echoCommand: String?): String {
            val lines = cleanBefore.split('\n').toMutableList()
            if (echoCommand != null && '\n' !in echoCommand) {
                val cmd = echoCommand.trim()
                val first = lines.firstOrNull()?.trimEnd('\r').orEmpty()
                val prompt = if (first.endsWith(cmd)) first.dropLast(cmd.length) else null
                // 只剥「提示符 + 命令」形态的回显：本地 shell 根本没有回显，只看「行尾等于命令」
                // 会把真实输出的首行当回显吃掉（如 pwd 输出 /data/.../pwd）
                if (prompt != null && prompt.length <= 24 && prompt.any { it in "~\$#>%" }) {
                    lines.removeAt(0)
                }
            }
            fun isEchoFragment(line: String): Boolean {
                val l = line.trim()
                if (l.isEmpty()) return false
                if ("\$__sess_status" in l || "\$__t" in l) return true
                if (token in l && !l.contains("$MARKER_PREFIX$token:")) return true
                val junk = l.filter { it !in " ~\$#>" }
                return junk.isNotEmpty() && junk.all { it in "\"'%:" } && junk.any { it in "\"'%" }
            }
            while (lines.isNotEmpty() && isEchoFragment(lines.first())) lines.removeAt(0)
            while (lines.isNotEmpty() && isEchoFragment(lines.last())) lines.removeAt(lines.lastIndex)
            return lines.joinToString("\n").trimEnd('\r', '\n')
        }
    }

    companion object {
        private const val LOG_TAG = "niki914_zafiro_BaseSessionBuiltin"
        private const val DEFAULT_TIMEOUT_SEC = 30L
        private const val POLL_INTERVAL_MS = 50L

        /** 会话已死且缓冲连续这么多拍没有新数据，才判定哨兵不会再来。 */
        private const val DEAD_QUIET_TICKS = 2

        /** 终端中断符 (Ctrl-C)，作为「作废当前命令」的显式信号。 */
        private const val ABORT_SEQUENCE = '\u0003'
    }
}

enum class SessionAction {
    CONNECT,
    LIST,
    READ,
    SUBMIT,
    WRITE,
    CLOSE;

    companion object {
        fun fromWireName(raw: String): SessionAction? {
            return when (raw.trim().lowercase()) {
                "connect" -> CONNECT
                "list" -> LIST
                "read" -> READ
                "submit" -> SUBMIT
                "write" -> WRITE
                "close" -> CLOSE
                else -> null
            }
        }
    }
}

open class BaseSessionArgs(
    val command: String? = null,
    val session: String? = null,
    val alias: String? = null,
    val action: SessionAction? = null,
    val text: String? = null,
    val timeout: Long? = null,
    val background: Boolean = false,
) {
    /**
     * 本次调用请求的执行身份，仅本地 shell 有（`user` / `root` / `shizuku`）；
     * ssh 与 py 工具无此概念，保持 null。
     *
     * 它只用于**开辟新会话**：已有会话的身份在创建时固化。安全前检不要读它，
     * 要用 [BaseSessionBuiltin.actualSessionIdentity] 取会话实际身份。
     */
    open val identity: String? get() = null
}
