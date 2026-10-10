package com.niki914.zafiro.chat.agentic.buildin.impl

import com.niki914.logging.Logger
import com.niki914.zafiro.chat.agentic.buildin.BuiltinTool
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolResult
import com.niki914.zafiro.chat.agentic.buildin.RawJsonBuiltinTool
import com.niki914.zafiro.chat.agentic.shell.TerminalCloseOutcome
import com.niki914.zafiro.chat.agentic.shell.TerminalInteractiveReadOutcome
import com.niki914.zafiro.chat.agentic.shell.TerminalInteractiveWriteOutcome
import com.niki914.zafiro.chat.agentic.shell.TerminalReadMode
import com.niki914.zafiro.chat.agentic.shell.TerminalReadOutcome
import com.niki914.zafiro.chat.agentic.shell.TerminalSessionPool
import com.niki914.zafiro.chat.agentic.shell.TerminalToolResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 会话式终端工具的统一抽象基类。
 * 负责别名管理 (Session Registry)、Sentinel 退出码解析、同步超时优雅降级、异步后台监听、通用 Action 分发以及回执自证。
 */
abstract class BaseSessionBuiltin : BuiltinTool(), RawJsonBuiltinTool {

    val sessionRegistry = SessionRegistry()

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
     * 当请求未提供 session 与 alias 时调用。
     * 子类可重写此方法（例如 ShellBuiltin 自动维护 alias="default" 的会话）。
     * 默认返回 null。
     */
    protected open suspend fun onDefaultSession(): String? = null

    /**
     * 解析目标 session_id。
     * 优先通过 SessionRegistry 查找别名或 ID，未传时尝试落入 onDefaultSession。
     */
    protected open suspend fun resolveTargetSessionId(explicitTarget: String?): String? {
        val trimmed = explicitTarget?.trim()?.takeIf(String::isNotBlank)
        if (trimmed != null) {
            return sessionRegistry.resolveSessionId(trimmed) ?: trimmed
        }
        return onDefaultSession()
    }

    // ── 通用命令执行引擎 (Sentinel + 同步等待 + 超时降级 + 异步后台) ───────────

    protected open suspend fun handleCommand(args: BaseSessionArgs): String {
        val target = args.session ?: args.alias
        val sessionId = resolveTargetSessionId(target)
            ?: return TerminalToolResponse.invalidRequest(
                "Field 'session' is required (or specify connection parameters)."
            )
        val command = args.command?.takeIf(String::isNotBlank)
            ?: return TerminalToolResponse.invalidRequest("Field 'command' must not be blank.")
        val timeoutSec = (args.timeout ?: DEFAULT_TIMEOUT_SEC).coerceAtLeast(1)

        return executeSentinelCommand(
            sessionId = sessionId,
            command = command,
            timeoutSec = timeoutSec,
            background = args.background,
        )
    }

    protected open suspend fun executeSentinelCommand(
        sessionId: String,
        command: String,
        timeoutSec: Long,
        background: Boolean,
    ): String {
        val meta = sessionRegistry.findById(sessionId)
        val token = randomToken()
        val payload = SessionSentinel.buildPayload(command, token)

        val initialOffset = readRawOutput(sessionId)?.length ?: 0

        val commandState = SessionCommandState(
            command = command,
            token = token,
            startTimeMs = System.currentTimeMillis(),
            timeoutMs = timeoutSec * 1000L,
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
                message = "Background command started. Use action='read' to poll output.",
            )
        }

        // 同步等待 Sentinel
        val parsed = awaitSentinel(sessionId, token, timeoutMs = timeoutSec * 1000L, initialOffset = initialOffset)
        return if (parsed != null) {
            commandState.status = if (parsed.exitCode == 0) "exited" else "failed"
            commandState.exitCode = parsed.exitCode
            commandState.cleanOutput = parsed.cleanOutput
            formatCommandResult(
                sessionId = sessionId,
                alias = meta?.alias,
                output = parsed.cleanOutput,
                exitCode = parsed.exitCode,
            )
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
        while (System.currentTimeMillis() < deadline) {
            val rawFull = readRawOutput(sessionId)
            if (rawFull != null) {
                val raw = if (rawFull.length >= initialOffset) rawFull.substring(initialOffset) else rawFull
                val parsed = SessionSentinel.parseOutput(raw, token)
                if (parsed != null) return parsed
            }
            delay(50L)
        }
        val finalRaw = readRawOutput(sessionId)
        val raw = if (finalRaw != null && finalRaw.length >= initialOffset) {
            finalRaw.substring(initialOffset)
        } else finalRaw
        return if (raw != null) SessionSentinel.parseOutput(raw, token) else null
    }

    protected open suspend fun sendInput(sessionId: String, text: String): Boolean {
        return when (TerminalSessionPool.writeInteractive(sessionId, text)) {
            is TerminalInteractiveWriteOutcome.Accepted -> true
            else -> false
        }
    }

    protected open fun readRawOutput(sessionId: String): String? {
        val outcome = TerminalSessionPool.readInteractive(
            session = sessionId,
            mode = TerminalReadMode.SNAPSHOT,
            maxBytes = Int.MAX_VALUE,
        )
        return if (outcome is TerminalInteractiveReadOutcome.Success) {
            outcome.stdout + outcome.stderr
        } else null
    }

    // ── 通用动作分发 ────────────────────────────────────────────────────────

    protected open suspend fun handleAction(args: BaseSessionArgs): String {
        return when (args.action) {
            SessionAction.LIST -> handleList()
            SessionAction.READ -> handleRead(args)
            SessionAction.SUBMIT -> handleSubmit(args)
            SessionAction.WRITE -> handleWrite(args)
            SessionAction.CLOSE -> handleClose(args)
            null -> TerminalToolResponse.invalidRequest("Field 'action' is required.")
        }
    }

    protected open fun handleList(): String {
        val sessions = sessionRegistry.all()
        val items = sessions.map { entry ->
            val payload = linkedMapOf<String, JsonElement>(
                "session_id" to JsonPrimitive(entry.sessionId),
                "created_at" to JsonPrimitive(entry.createdAt),
            )
            entry.alias?.let { payload["alias"] = JsonPrimitive(it) }
            if (entry.metadata.isNotEmpty()) {
                payload["metadata"] = JsonObject(entry.metadata.mapValues { JsonPrimitive(it.value) })
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
        val target = args.session ?: args.alias
        val sessionId = resolveTargetSessionId(target)
            ?: return TerminalToolResponse.invalidRequest("Field 'session' is required for action='read'.")
        val meta = sessionRegistry.findById(sessionId)
        val activeCommand = meta?.activeCommand

        // 若会话此前通过 Sentinel 下发了命令，以 Sentinel 状态机检测为准
        if (activeCommand != null) {
            val rawFull = readRawOutput(sessionId)
            val raw = if (rawFull != null && rawFull.length >= activeCommand.initialOffset) {
                rawFull.substring(activeCommand.initialOffset)
            } else rawFull.orEmpty()

            if (activeCommand.status == "running" && raw.isNotEmpty()) {
                val parsed = SessionSentinel.parseOutput(raw, activeCommand.token)
                if (parsed != null) {
                    activeCommand.status = if (parsed.exitCode == 0) "exited" else "failed"
                    activeCommand.exitCode = parsed.exitCode
                    activeCommand.cleanOutput = parsed.cleanOutput
                }
            }
            val elapsed = ((System.currentTimeMillis() - activeCommand.startTimeMs) / 1000L).coerceAtLeast(0L)
            return if (activeCommand.status != "running") {
                formatReadResult(
                    sessionId = sessionId,
                    status = activeCommand.status,
                    output = activeCommand.cleanOutput,
                    exitCode = activeCommand.exitCode,
                    elapsedSeconds = elapsed,
                )
            } else {
                formatReadResult(
                    sessionId = sessionId,
                    status = "running",
                    output = raw,
                    exitCode = null,
                    elapsedSeconds = elapsed,
                )
            }
        }

        // 兜底：直接从 TerminalSessionPool 中读交互式输出
        val mode = args.mode ?: TerminalReadMode.DELTA
        val maxBytes = args.maxBytes ?: DEFAULT_MAX_BYTES

        return when (val interactiveOutcome = TerminalSessionPool.readInteractive(sessionId, mode, maxBytes)) {
            is TerminalInteractiveReadOutcome.Success -> formatReadResult(
                sessionId = sessionId,
                status = "running",
                output = interactiveOutcome.stdout + interactiveOutcome.stderr,
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
        val target = args.session ?: args.alias
        val sessionId = resolveTargetSessionId(target)
            ?: return TerminalToolResponse.invalidRequest("Field 'session' is required for action='submit'.")
        val text = args.text
            ?: return TerminalToolResponse.invalidRequest("Field 'text' is required for submit.")
        return writeInteractivePayload(sessionId, "$text\n")
    }

    protected open suspend fun handleWrite(args: BaseSessionArgs): String {
        val target = args.session ?: args.alias
        val sessionId = resolveTargetSessionId(target)
            ?: return TerminalToolResponse.invalidRequest("Field 'session' is required for action='write'.")
        val text = args.text
            ?: return TerminalToolResponse.invalidRequest("Field 'text' is required for write.")
        return writeInteractivePayload(sessionId, text)
    }

    protected open suspend fun handleClose(args: BaseSessionArgs): String {
        val target = args.session ?: args.alias
        val sessionId = resolveTargetSessionId(target)
            ?: return TerminalToolResponse.invalidRequest("Field 'session' is required for action='close'.")
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
    ): String {
        val meta = sessionRegistry.findById(sessionId)
        val payload = linkedMapOf<String, JsonElement>(
            "session_id" to JsonPrimitive(sessionId),
            "status" to JsonPrimitive(status),
            "output" to JsonPrimitive(output),
            "elapsed_seconds" to JsonPrimitive(elapsedSeconds),
        )
        meta?.alias?.let { payload["alias"] = JsonPrimitive(it) }
        exitCode?.let { payload["exit_code"] = JsonPrimitive(it) }
        return JsonObject(payload).toString()
    }

    protected fun formatWriteResult(sessionId: String, bytesWritten: Int): String {
        val meta = sessionRegistry.findById(sessionId)
        val payload = linkedMapOf<String, JsonElement>(
            "session_id" to JsonPrimitive(sessionId),
            "bytes_written" to JsonPrimitive(bytesWritten),
        )
        meta?.alias?.let { payload["alias"] = JsonPrimitive(it) }
        return JsonObject(payload).toString()
    }

    protected fun formatCommandResult(
        sessionId: String,
        alias: String?,
        output: String,
        exitCode: Int,
        extra: Map<String, JsonElement> = emptyMap(),
    ): String {
        val payload = linkedMapOf<String, JsonElement>(
            "session_id" to JsonPrimitive(sessionId),
            "output" to JsonPrimitive(output),
            "exit_code" to JsonPrimitive(exitCode),
            "status" to JsonPrimitive(if (exitCode == 0) "exited" else "failed"),
        )
        alias?.let { payload["alias"] = JsonPrimitive(it) }
        payload.putAll(extra)
        return JsonObject(payload).toString()
    }

    protected fun formatBackgroundAccepted(
        sessionId: String,
        alias: String?,
        message: String,
    ): String {
        val payload = linkedMapOf<String, JsonElement>(
            "session_id" to JsonPrimitive(sessionId),
            "background" to JsonPrimitive(true),
            "status" to JsonPrimitive("running"),
            "output" to JsonPrimitive(message),
        )
        alias?.let { payload["alias"] = JsonPrimitive(it) }
        return JsonObject(payload).toString()
    }

    protected fun formatCommandTimedOut(
        sessionId: String,
        alias: String?,
        output: String,
        timeoutSec: Long,
    ): String {
        val payload = linkedMapOf<String, JsonElement>(
            "session_id" to JsonPrimitive(sessionId),
            "status" to JsonPrimitive("running"),
            "timed_out" to JsonPrimitive(true),
            "output" to JsonPrimitive(output),
            "message" to JsonPrimitive(
                "Command did not finish within ${timeoutSec}s. It continues running in the background; use action='read' to poll output."
            ),
        )
        alias?.let { payload["alias"] = JsonPrimitive(it) }
        return JsonObject(payload).toString()
    }

    private fun randomToken(): String {
        return UUID.randomUUID().toString().replace("-", "").take(8)
    }

    // ── 内部类型与对象 ──────────────────────────────────────────────────────

    data class SessionCommandState(
        val command: String,
        val token: String,
        val startTimeMs: Long,
        val timeoutMs: Long,
        val initialOffset: Int = 0,
        @Volatile var status: String = "running",
        @Volatile var exitCode: Int? = null,
        @Volatile var cleanOutput: String = "",
        @Volatile var timedOut: Boolean = false,
    )

    data class SessionMeta(
        val sessionId: String,
        val alias: String?,
        val createdAt: Long = System.currentTimeMillis(),
        val metadata: Map<String, String> = emptyMap(),
        @Volatile var activeCommand: SessionCommandState? = null,
    )

    class SessionRegistry {
        private val byId = ConcurrentHashMap<String, SessionMeta>()
        private val byAlias = ConcurrentHashMap<String, String>()

        fun register(
            sessionId: String,
            alias: String?,
            metadata: Map<String, String> = emptyMap(),
        ): SessionMeta {
            if (alias != null) {
                val existingSession = byAlias.putIfAbsent(alias, sessionId)
                if (existingSession != null && existingSession != sessionId) {
                    throw IllegalArgumentException(
                        "Session alias '$alias' is already in use by session '$existingSession'."
                    )
                }
            }
            val meta = SessionMeta(sessionId = sessionId, alias = alias, metadata = metadata)
            byId[sessionId] = meta
            return meta
        }

        fun resolveSessionId(target: String?): String? {
            if (target.isNullOrBlank()) return null
            return byAlias[target] ?: if (byId.containsKey(target)) target else null
        }

        fun findById(sessionId: String): SessionMeta? = byId[sessionId]

        fun findByAlias(alias: String): SessionMeta? {
            val id = byAlias[alias] ?: return null
            return byId[id]
        }

        fun updateCommandState(sessionId: String, state: SessionCommandState?) {
            byId[sessionId]?.activeCommand = state
        }

        fun getCommandState(sessionId: String): SessionCommandState? {
            return byId[sessionId]?.activeCommand
        }

        fun unregister(sessionId: String): SessionMeta? {
            val meta = byId.remove(sessionId)
            if (meta?.alias != null) {
                byAlias.remove(meta.alias, sessionId)
            }
            return meta
        }

        fun all(): List<SessionMeta> = byId.values.toList()

        fun clear() {
            byId.clear()
            byAlias.clear()
        }
    }

    object SessionSentinel {
        const val MARKER_PREFIX = "__SESSION_EXIT_"
        const val MARKER_SUFFIX = "__"

        fun buildPayload(command: String, token: String): String {
            val normalizedCommand = if (command.endsWith('\n')) command else "$command\n"
            return buildString {
                append(normalizedCommand)
                append("__sess_status=$?; printf '\\n")
                append(MARKER_PREFIX)
                append(token)
                append(":%s")
                append(MARKER_SUFFIX)
                append("\\n' \"\$__sess_status\"\n")
            }
        }

        data class Parsed(
            val exitCode: Int,
            val cleanOutput: String,
        )

        fun parseOutput(rawOutput: String, token: String): Parsed? {
            val tokenPrefix = "$MARKER_PREFIX$token:"
            val prefixIndex = rawOutput.lastIndexOf(tokenPrefix)
            if (prefixIndex < 0) return null
            val suffixIndex = rawOutput.indexOf(MARKER_SUFFIX, prefixIndex + tokenPrefix.length)
            if (suffixIndex < 0) return null

            val exitCodeStr = rawOutput.substring(prefixIndex + tokenPrefix.length, suffixIndex)
            val exitCode = exitCodeStr.trim().toIntOrNull() ?: return null

            var cleanBefore = rawOutput.substring(0, prefixIndex).trimEnd('\r', '\n')

            val echoPattern = "$MARKER_PREFIX$token:%s$MARKER_SUFFIX"
            val echoIndex = cleanBefore.lastIndexOf(echoPattern)
            if (echoIndex >= 0) {
                val lineStart = cleanBefore.lastIndexOf('\n', echoIndex).let { if (it < 0) 0 else it + 1 }
                cleanBefore = cleanBefore.substring(0, lineStart).trimEnd('\r', '\n')
            }

            val cleanAfter = rawOutput.substring(suffixIndex + MARKER_SUFFIX.length).trimStart('\r', '\n')
            val clean = when {
                cleanBefore.isEmpty() -> cleanAfter
                cleanAfter.isEmpty() -> cleanBefore
                else -> "$cleanBefore\n$cleanAfter"
            }

            return Parsed(exitCode = exitCode, cleanOutput = clean)
        }
    }

    companion object {
        private const val LOG_TAG = "niki914_zafiro_BaseSessionBuiltin"
        private const val DEFAULT_TIMEOUT_SEC = 30L
        private const val DEFAULT_MAX_BYTES = 8192
    }
}

enum class SessionAction {
    LIST,
    READ,
    SUBMIT,
    WRITE,
    CLOSE;

    companion object {
        fun fromWireName(raw: String): SessionAction? {
            return when (raw.trim().lowercase()) {
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
    val notifyOnComplete: Boolean = false,
    val mode: TerminalReadMode? = null,
    val maxBytes: Int? = null,
)
