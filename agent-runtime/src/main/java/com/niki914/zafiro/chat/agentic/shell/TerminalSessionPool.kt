// githooks:ignore-file monitor-lock 终端进程句柄池采用细粒度监视器锁保障原子状态切换
package com.niki914.zafiro.chat.agentic.shell

import com.niki914.libterm.OpenResult
import com.niki914.libterm.OutputStream
import com.niki914.libterm.SessionState
import com.niki914.libterm.SshAuth
import com.niki914.libterm.SshOpenOptions
import com.niki914.libterm.TerminalFailure
import com.niki914.libterm.TerminalIdentity
import com.niki914.libterm.runtime.CommandResult
import com.niki914.libterm.runtime.LibTerm
import com.niki914.libterm.runtime.LibTermRuntime
import com.niki914.libterm.runtime.LibTermSession
import com.niki914.libterm.runtime.TermResult
import com.niki914.libterm.runtime.TerminalTextChunk
import com.niki914.logging.Logger
import com.niki914.xposed.api.util.ContextProvider
import com.niki914.zafiro.chat.agentic.AgentTenantContext
import com.niki914.zafiro.chat.agentic.shell.TerminalSessionPool.completeAsync
import com.niki914.zafiro.chat.agentic.shell.TerminalSessionPool.readSession
import com.niki914.zafiro.chat.agentic.shell.TerminalToolResponse.stderrText
import com.niki914.zafiro.chat.agentic.shell.TerminalToolResponse.stdoutText
import com.niki914.zafiro.util.ToolOutputTruncator
import com.niki914.zafiro.api.util.runCatchingCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.util.UUID
import kotlin.random.Random

object TerminalSessionPool {
    private const val LOG_TAG = "niki914_zafiro_TerminalSessionPool"
    private const val CUSTOM_TOOL_SESSION = "__custom_user"
    private const val SSH_PUBLIC_IDENTITY = "ssh"
    private const val PUBLIC_HANDLE_LENGTH = 4
    private const val MAX_HANDLE_GENERATION_ATTEMPTS = 64
    private const val DEFAULT_INTERACTIVE_READ_MAX_BYTES = 8192

    // 与 ToolOutputTruncator.DEFAULT_MAX_BYTES 对齐；readSession 截断由统一入口处理并导出全量
    // FIXME(async-refactor): 异步轮询链路重构时一并收口 readSession 的上限与导出语义
    private const val DEFAULT_READ_MAX_BYTES = ToolOutputTruncator.DEFAULT_MAX_BYTES
    private const val GENERATED_HANDLE_COLLISION_MESSAGE = "Generated session handle collision."
    private val PUBLIC_HANDLE_REGEX: Regex = Regex("^[0-9a-f]{4}$")
    private val lock = Any()
    private var runtimeHolder: RuntimeHolder? = null
    private val sessions: MutableMap<String, TerminalSessionEntry> = linkedMapOf()
    private val asyncStates: MutableMap<String, AsyncState> = linkedMapOf()
    private val interactiveStates: MutableMap<String, InteractiveState> = linkedMapOf()
    private val executionLocks: MutableMap<String, Mutex> = linkedMapOf()
    private val pendingNotifications = mutableListOf<String>()
    private var handleGenerator: () -> String = ::randomPublicHandle
    private var runtimePortFactory: suspend (CoroutineScope) -> TerminalRuntimePort =
        ::createLibTermRuntimePort

    suspend fun open(
        identity: String,
        cwd: String? = null,
        collectOutput: Boolean = false,
    ): TerminalOpenOutcome {
        val mappedIdentity = try {
            mapIdentity(identity)
        } catch (error: IllegalArgumentException) {
            return TerminalOpenOutcome.InvalidRequest(error.message.orEmpty())
        }
        return openWithGeneratedHandle(
            publicIdentity = identity.trim(),
            terminalIdentity = mappedIdentity,
            cwd = cwd,
            sshOptions = null,
            collectOutput = collectOutput,
        )
    }

    suspend fun openSsh(options: SshOpenOptions, cwd: String? = null): TerminalOpenOutcome {
        return openWithGeneratedHandle(
            publicIdentity = SSH_PUBLIC_IDENTITY,
            terminalIdentity = TerminalIdentity.Ssh,
            cwd = cwd,
            sshOptions = options,
            collectOutput = true,
        )
    }

    suspend fun openAndExecute(
        identity: String,
        cwd: String?,
        command: String,
        timeoutMs: Long,
    ): TerminalCommandOutcome {
        return when (val openOutcome = open(identity = identity, cwd = cwd)) {
            is TerminalOpenOutcome.Success -> executeBlocking(
                session = openOutcome.session,
                command = command,
                timeoutMs = timeoutMs,
            )

            is TerminalOpenOutcome.Failure -> TerminalCommandOutcome.Failure(
                session = null,
                identity = identity.trim().takeIf { it.isNotBlank() },
                failure = openOutcome.failure,
                elapsedSeconds = openOutcome.elapsedSeconds,
            )

            is TerminalOpenOutcome.InvalidRequest -> TerminalCommandOutcome.UnexpectedError(
                session = null,
                throwable = IllegalArgumentException(openOutcome.message),
                elapsedSeconds = 0L,
            )
        }
    }

    suspend fun openAndExecuteSsh(
        options: SshOpenOptions,
        cwd: String?,
        command: String,
        timeoutMs: Long,
    ): TerminalCommandOutcome {
        return when (val openOutcome = openSsh(options = options, cwd = cwd)) {
            is TerminalOpenOutcome.Success -> executeBlocking(
                session = openOutcome.session,
                command = command,
                timeoutMs = timeoutMs,
            )

            is TerminalOpenOutcome.Failure -> TerminalCommandOutcome.Failure(
                session = null,
                identity = SSH_PUBLIC_IDENTITY,
                failure = openOutcome.failure,
                elapsedSeconds = openOutcome.elapsedSeconds,
            )

            is TerminalOpenOutcome.InvalidRequest -> TerminalCommandOutcome.UnexpectedError(
                session = null,
                throwable = IllegalArgumentException(openOutcome.message),
                elapsedSeconds = 0L,
            )
        }
    }

    suspend fun executeCustomCommand(command: String, timeoutMs: Long): TerminalCommandOutcome {
        return when (val openOutcome = openSession(
            handle = CUSTOM_TOOL_SESSION,
            publicIdentity = "user",
            terminalIdentity = TerminalIdentity.User,
            cwd = null,
            sshOptions = null,
            collectOutput = false,
            reuseExisting = true,
        )) {
            is TerminalOpenOutcome.Success -> executeBlocking(
                session = openOutcome.session,
                command = command,
                timeoutMs = timeoutMs,
            )

            is TerminalOpenOutcome.Failure -> TerminalCommandOutcome.Failure(
                session = CUSTOM_TOOL_SESSION,
                identity = "user",
                failure = openOutcome.failure,
                elapsedSeconds = openOutcome.elapsedSeconds,
            )

            is TerminalOpenOutcome.InvalidRequest -> TerminalCommandOutcome.UnexpectedError(
                session = null,
                throwable = IllegalArgumentException(openOutcome.message),
                elapsedSeconds = 0L,
            )
        }
    }

    private suspend fun openWithGeneratedHandle(
        publicIdentity: String,
        terminalIdentity: TerminalIdentity,
        cwd: String?,
        sshOptions: SshOpenOptions?,
        collectOutput: Boolean,
    ): TerminalOpenOutcome {
        repeat(MAX_HANDLE_GENERATION_ATTEMPTS) {
            val handle = try {
                generateAvailablePublicHandle()
            } catch (error: IllegalStateException) {
                return TerminalOpenOutcome.InvalidRequest(error.message.orEmpty())
            }
            when (val outcome = openSession(
                handle = handle,
                publicIdentity = publicIdentity,
                terminalIdentity = terminalIdentity,
                cwd = cwd,
                sshOptions = sshOptions,
                collectOutput = collectOutput,
                reuseExisting = false,
            )) {
                is TerminalOpenOutcome.InvalidRequest -> {
                    if (outcome.message == GENERATED_HANDLE_COLLISION_MESSAGE) {
                        return@repeat
                    }
                    return outcome
                }

                else -> return outcome
            }
        }
        return TerminalOpenOutcome.InvalidRequest(
            "Unable to allocate terminal session handle after $MAX_HANDLE_GENERATION_ATTEMPTS attempts.",
        )
    }

    private suspend fun openSession(
        handle: String,
        publicIdentity: String,
        terminalIdentity: TerminalIdentity,
        cwd: String? = null,
        sshOptions: SshOpenOptions? = null,
        collectOutput: Boolean,
        reuseExisting: Boolean,
    ): TerminalOpenOutcome {
        val startTimeMs = System.currentTimeMillis()
        synchronized(lock) {
            sessions[handle]?.let { existing ->
                if (!reuseExisting) {
                    return TerminalOpenOutcome.InvalidRequest(GENERATED_HANDLE_COLLISION_MESSAGE)
                }
                Logger.d(
                    LOG_TAG,
                    "session reused handle=${existing.handle} identity=${existing.identity}"
                )
                return TerminalOpenOutcome.Success(
                    session = existing.handle,
                    identity = existing.identity,
                )
            }
        }

        val holder = runtime()
        return when (val result = holder.runtime.open(
            identity = terminalIdentity,
            cwd = cwd,
            sshOptions = sshOptions,
        )) {
            is OpenResult.Success -> {
                val callerTenantId =
                    kotlin.coroutines.coroutineContext[AgentTenantContext]?.tenantId
                val entry = TerminalSessionEntry(
                    handle = handle,
                    identity = publicIdentity,
                    session = result.value,
                    libTermSessionId = result.value.id,
                    cwd = cwd,
                    tenantId = callerTenantId,
                )
                val existing = synchronized(lock) {
                    val current = sessions[handle]
                    if (current == null) {
                        sessions[handle] = entry
                        executionLocks.getOrPut(handle) { Mutex() }
                        null
                    } else {
                        current
                    }
                }
                if (existing != null) {
                    runCatching { holder.runtime.close(result.value.id) }
                    if (reuseExisting) {
                        TerminalOpenOutcome.Success(
                            session = existing.handle,
                            identity = existing.identity,
                        )
                    } else {
                        TerminalOpenOutcome.InvalidRequest(GENERATED_HANDLE_COLLISION_MESSAGE)
                    }
                } else {
                    if (collectOutput) {
                        startInteractiveCollector(entry = entry, holder = holder)
                    }
                    Logger.i(
                        LOG_TAG,
                        "session opened handle=${entry.handle} identity=${entry.identity} " +
                                "libTermSessionId=${entry.libTermSessionId} " +
                                "elapsedMs=${System.currentTimeMillis() - startTimeMs}"
                    )
                    TerminalOpenOutcome.Success(
                        session = entry.handle,
                        identity = entry.identity,
                    )
                }
            }

            is OpenResult.Failure -> {
                Logger.w(
                    LOG_TAG,
                    "session open failed identity=$publicIdentity failure=${result.failure} " +
                            "elapsedMs=${System.currentTimeMillis() - startTimeMs}"
                )
                TerminalOpenOutcome.Failure(
                    failure = result.failure,
                    elapsedSeconds = elapsedSeconds(startTimeMs),
                )
            }
        }
    }

    private suspend fun getTenantSession(session: String): TerminalSessionEntry? {
        val entry = synchronized(lock) { sessions[session] } ?: return null
        val callerTenantId = kotlin.coroutines.coroutineContext[AgentTenantContext]?.tenantId
        if (callerTenantId != null && callerTenantId != entry.tenantId) {
            Logger.w(
                LOG_TAG,
                "access denied: session $session belongs to tenant=${entry.tenantId}, caller=$callerTenantId"
            )
            return null
        }
        return entry
    }

    suspend fun writeInteractive(
        session: String,
        text: String,
        requestId: String? = null,
    ): TerminalInteractiveWriteOutcome {
        val entry = getTenantSession(session)
            ?: return TerminalInteractiveWriteOutcome.SessionNotFound(session)
        val state = synchronized(lock) {
            interactiveStates[session] ?: return TerminalInteractiveWriteOutcome.NotInteractive(
                session
            )
        }
        val replay = requestId?.let { id ->
            synchronized(state.lock) {
                state.writeResults[id]
            }
        }
        if (replay != null) {
            return TerminalInteractiveWriteOutcome.Accepted(
                bytesWritten = replay.bytesWritten,
                sequence = replay.sequence,
                replayed = true,
            )
        }

        if (!state.inputLock.tryLock()) {
            return TerminalInteractiveWriteOutcome.Busy(session)
        }
        return runCatchingCancellable {
            try {
                entry.session.write(text)
                val writeResult = synchronized(state.lock) {
                    state.inputSequence += 1L
                    InteractiveWriteResult(
                        bytesWritten = text.encodeToByteArray().size,
                        sequence = state.inputSequence,
                    ).also { result ->
                        requestId?.takeIf(String::isNotBlank)?.let { id ->
                            state.writeResults[id] = result
                        }
                    }
                }
                TerminalInteractiveWriteOutcome.Accepted(
                    bytesWritten = writeResult.bytesWritten,
                    sequence = writeResult.sequence,
                    replayed = false,
                )
            } finally {
                state.inputLock.unlock()
            }
        }.getOrElse { error ->
            TerminalInteractiveWriteOutcome.UnexpectedError(error)
        }
    }

    suspend fun readInteractive(
        session: String,
        mode: TerminalReadMode = TerminalReadMode.DELTA,
        maxBytes: Int = DEFAULT_INTERACTIVE_READ_MAX_BYTES,
    ): TerminalInteractiveReadOutcome {
        val entry = getTenantSession(session)
            ?: return TerminalInteractiveReadOutcome.SessionNotFound(session)
        val state = synchronized(lock) {
            interactiveStates[session] ?: return TerminalInteractiveReadOutcome.NotInteractive(
                session
            )
        }
        val maxChars = maxBytes.coerceAtLeast(1)
        val snapshot = synchronized(state.lock) {
            val raw = when (mode) {
                TerminalReadMode.DELTA -> state.text.substring(state.deltaOffset)
                TerminalReadMode.SNAPSHOT -> state.text.toString()
            }
            if (mode == TerminalReadMode.DELTA) {
                state.deltaOffset = state.text.length
            }
            InteractiveReadSnapshot(
                text = raw.takeLast(maxChars),
                sequence = state.outputSequence,
                truncated = raw.length > maxChars,
            )
        }
        return TerminalInteractiveReadOutcome.Success(
            text = snapshot.text,
            mode = mode,
            sequence = snapshot.sequence,
            truncated = snapshot.truncated,
        )
    }

    internal fun get(session: String): TerminalSessionEntry? {
        return synchronized(lock) {
            sessions[session]
        }
    }

    /**
     * 会话生死：池里还在，且交互流没有终结，且底层会话未退出。
     * SSH 远端掉线 / 本地 shell 退出时，仅靠池条目存在与否会把僵尸会话当成活的。
     */
    fun isSessionAlive(session: String): Boolean {
        // githooks:ignore monitor-lock 与既有锁域同源：sessions / interactiveStates / ended 都由本对象锁保护
        return synchronized(lock) {
            val entry = sessions[session] ?: return@synchronized false
            if (!entry.session.isAlive) return@synchronized false
            val state = interactiveStates[session] ?: return@synchronized true
            // githooks:ignore monitor-lock state.ended 由 interactiveState 自己的锁保护，与既有采集处一致
            synchronized(state.lock) { !state.ended }
        }
    }

    suspend fun executeBlocking(
        session: String,
        command: String,
        timeoutMs: Long,
    ): TerminalCommandOutcome {
        val startTimeMs = System.currentTimeMillis()
        val entry =
            getTenantSession(session) ?: return TerminalCommandOutcome.SessionNotFound(session)
        val executeLock = synchronized(lock) {
            executionLocks.getOrPut(session) { Mutex() }
        }
        if (!executeLock.tryLock()) {
            Logger.d(LOG_TAG, "command busy session=$session")
            return TerminalCommandOutcome.Busy(
                session = session,
                asyncId = currentAsyncId(session),
            )
        }

        Logger.i(
            LOG_TAG,
            "command start session=$session commandLength=${command.length} timeoutMs=$timeoutMs"
        )
        return try {
            runCatchingCancellable {
                when (val result = entry.session.exec(command = command, timeoutMillis = timeoutMs)) {
                    is TermResult.Success -> {
                        if (result.value.timedOut) {
                            Logger.i(
                                LOG_TAG,
                                "command timed out session=$session " +
                                        "elapsedMs=${System.currentTimeMillis() - startTimeMs}"
                            )
                            TerminalCommandOutcome.Timeout(
                                session = entry.handle,
                                identity = entry.identity,
                                result = result.value,
                                elapsedSeconds = elapsedSeconds(startTimeMs),
                            )
                        } else {
                            Logger.i(
                                LOG_TAG,
                                "command done session=$session exitCode=${result.value.exitCode} " +
                                        "elapsedMs=${System.currentTimeMillis() - startTimeMs}"
                            )
                            TerminalCommandOutcome.Success(
                                session = entry.handle,
                                identity = entry.identity,
                                result = result.value,
                                elapsedSeconds = elapsedSeconds(startTimeMs),
                            )
                        }
                    }

                    is TermResult.Failure -> {
                        Logger.w(
                            LOG_TAG,
                            "command failed session=$session failure=${result.failure} " +
                                    "elapsedMs=${System.currentTimeMillis() - startTimeMs}"
                        )
                        TerminalCommandOutcome.Failure(
                            session = entry.handle,
                            identity = entry.identity,
                            failure = result.failure,
                            elapsedSeconds = elapsedSeconds(startTimeMs),
                        )
                    }
                }
            }.getOrElse { error ->
                Logger.w(
                    LOG_TAG,
                    "command unexpected error session=$session " +
                            "errorType=${error::class.simpleName} message=${error.message} " +
                            "elapsedMs=${System.currentTimeMillis() - startTimeMs}"
                )
                TerminalCommandOutcome.UnexpectedError(
                    session = entry.handle,
                    throwable = error,
                    elapsedSeconds = elapsedSeconds(startTimeMs),
                )
            }
        } finally {
            executeLock.unlock()
        }
    }

    suspend fun startAsync(
        session: String,
        command: String,
        timeoutMs: Long,
        notifyOnComplete: Boolean = false,
    ): TerminalAsyncStartOutcome {
        val startTimeMs = System.currentTimeMillis()
        val entry = getTenantSession(session)
            ?: return TerminalAsyncStartOutcome.SessionNotFound(session)
        val (executeLock, holder) = synchronized(lock) {
            val executeLock = executionLocks.getOrPut(session) { Mutex() }
            val holder = runtimeHolder ?: return TerminalAsyncStartOutcome.InvalidRequest(
                "Terminal runtime is not initialized. Use open or open_and_exec first.",
            )
            executeLock to holder
        }
        if (!executeLock.tryLock()) {
            Logger.d(LOG_TAG, "async busy session=$session")
            return TerminalAsyncStartOutcome.Busy(
                session = session,
                asyncId = currentAsyncId(session),
            )
        }

        Logger.i(
            LOG_TAG,
            "async start session=$session commandLength=${command.length} timeoutMs=$timeoutMs"
        )

        val asyncId = UUID.randomUUID().toString()
        val stdoutPartial = StringBuilder()
        val stderrPartial = StringBuilder()
        val stateLock = Any()
        lateinit var state: AsyncState
        val collectorJob = holder.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            entry.session.stream.collect { chunk ->
                synchronized(stateLock) {
                    when (chunk.stream) {
                        OutputStream.STDOUT -> stdoutPartial.append(chunk.text)
                        OutputStream.STDERR -> stderrPartial.append(chunk.text)
                    }
                }
            }
        }
        val execJob = holder.scope.launch(start = CoroutineStart.LAZY) {
            runCatchingCancellable {
                when (val result =
                    entry.session.exec(command = command, timeoutMillis = timeoutMs)) {
                    is TermResult.Success -> synchronized(state.lock) {
                        state.result = result.value
                    }

                    is TermResult.Failure -> synchronized(state.lock) {
                        state.failure = result.failure
                    }
                }
            }.onFailure { error ->
                synchronized(state.lock) {
                    state.unexpectedError = error
                }
            }
        }
        state = AsyncState(
            asyncId = asyncId,
            execJob = execJob,
            collectorJob = collectorJob,
            startTimeMs = startTimeMs,
            stdoutPartial = stdoutPartial,
            stderrPartial = stderrPartial,
            lock = stateLock,
            notifyOnComplete = notifyOnComplete,
        )
        synchronized(lock) {
            asyncStates[session] = state
        }
        // Register the completion callback BEFORE start() so the Lazy coroutine
        // cannot complete before the callback is attached.
        execJob.invokeOnCompletion { cause ->
            if (cause == null) {
                // Normal completion (not cancelled): store result, release Mutex, enqueue notification.
                onAsyncCompleted(session, state)
            } else {
                // Cancelled: still release resources.
                completeAsync(session, state)
            }
        }
        execJob.start()

        Logger.d(LOG_TAG, "async accepted session=$session asyncId=$asyncId")
        return TerminalAsyncStartOutcome.Accepted(
            asyncId = asyncId,
            elapsedSeconds = elapsedSeconds(startTimeMs),
        )
    }

    suspend fun readSession(
        session: String,
        mode: TerminalReadMode = TerminalReadMode.DELTA,
        maxBytes: Int = DEFAULT_READ_MAX_BYTES,
    ): TerminalReadOutcome {
        // 1. Check the session exists and belongs to this tenant.
        val entry = getTenantSession(session) ?: return TerminalReadOutcome.SessionNotFound(session)
        // 完成字段、异常结果与 asyncStates 必须在同一个监视器临界区里取快照：
        // onAsyncCompleted 是“先在 state.lock 下写完成字段、再单独进 lock 从 asyncStates 移除”
        // 两段临界区，分开读会落在两者之间，把刚跑完的任务误判成 NotBackground。
        val snapshot = synchronized(lock) {
            ReadSnapshot(
                result = entry.completedResult,
                failure = entry.completedFailure,
                unexpectedError = entry.completedUnexpectedError,
                elapsedSeconds = entry.completedElapsedSeconds,
                asyncState = asyncStates[session],
            )
        }
        val completedResult = snapshot.result
        val completedFailure = snapshot.failure
        val completedElapsed = snapshot.elapsedSeconds
        val completedUnexpectedError = snapshot.unexpectedError
        // 2. Completed with a result.
        if (completedResult != null) {
            val output = truncateOutput(mergedOutput(completedResult), maxBytes, exportDir())
            return if (completedResult.timedOut) {
                TerminalReadOutcome.TimedOut(
                    session = session,
                    output = output,
                    elapsedSeconds = completedElapsed,
                )
            } else {
                TerminalReadOutcome.Exited(
                    session = session,
                    output = output,
                    exitCode = completedResult.exitCode ?: -1,
                    elapsedSeconds = completedElapsed,
                )
            }
        }
        // 3. Completed with a failure.
        if (completedFailure != null) {
            return TerminalReadOutcome.Exited(
                session = session,
                output = completedFailure.message ?: "Command failed.",
                exitCode = -1,
                elapsedSeconds = completedElapsed,
            )
        }
        // 3b. Completed with an unexpected error (exception thrown inside exec).
        if (completedUnexpectedError != null) {
            return TerminalReadOutcome.Crashed(
                session = session,
                errorMessage = completedUnexpectedError.message ?: "Command crashed.",
                elapsedSeconds = completedElapsed,
            )
        }
        // 4. Still running: partial output (delta or snapshot).
        val asyncState = snapshot.asyncState
        if (asyncState != null) {
            val output = synchronized(asyncState.lock) {
                when (mode) {
                    TerminalReadMode.DELTA -> {
                        val stdoutDelta =
                            asyncState.stdoutPartial.substring(asyncState.stdoutDeltaOffset)
                        val stderrDelta =
                            asyncState.stderrPartial.substring(asyncState.stderrDeltaOffset)
                        asyncState.stdoutDeltaOffset = asyncState.stdoutPartial.length
                        asyncState.stderrDeltaOffset = asyncState.stderrPartial.length
                        stdoutDelta + stderrDelta
                    }

                    TerminalReadMode.SNAPSHOT -> asyncState.stdoutPartial.toString() + asyncState.stderrPartial.toString()
                }
            }
            return TerminalReadOutcome.Running(
                session = session,
                output = truncateOutput(output, maxBytes, exportDir()),
                elapsedSeconds = elapsedSeconds(asyncState.startTimeMs),
            )
        }
        // 5. Exists but neither running nor completed: not a background task.
        return TerminalReadOutcome.NotBackground(session)
    }

    /**
     * Drains and clears the queued background-completion notifications
     * (enqueued when notify_on_complete is set), for injection into the next
     * user message by the upper layer.
     */
    fun drainPendingNotifications(): List<String> {
        return synchronized(lock) {
            val list = pendingNotifications.toList()
            pendingNotifications.clear()
            list
        }
    }

    suspend fun close(session: String): TerminalCloseOutcome {
        val target = getTenantSession(session) ?: return TerminalCloseOutcome.Closed
        val removed = synchronized(lock) {
            RemovedSession(
                entry = sessions.remove(session),
                asyncState = asyncStates.remove(session),
                interactiveState = interactiveStates.remove(session),
                executionLock = executionLocks.remove(session),
            )
        }
        removed.asyncState?.let { state ->
            state.execJob.cancel()
            state.collectorJob.cancel()
            unlockIfLocked(removed.executionLock)
        }
        removed.interactiveState?.collectorJob?.cancel()
        val entry = removed.entry ?: return TerminalCloseOutcome.Closed
        return runCatchingCancellable {
            entry.session.close()
            Logger.d(LOG_TAG, "session closed handle=$session")
            TerminalCloseOutcome.Closed
        }.getOrElse { error ->
            Logger.w(
                LOG_TAG,
                "session close failed handle=$session errorType=${error::class.simpleName} " +
                        "message=${error.message}"
            )
            TerminalCloseOutcome.UnexpectedError(error)
        }
    }

    suspend fun closeAll(): TerminalCloseAllOutcome {
        val removed = synchronized(lock) {
            val removed = RemovedAll(
                holder = runtimeHolder,
                sessionCount = sessions.size,
                asyncStates = asyncStates.toMap(),
                interactiveStates = interactiveStates.toMap(),
                executionLocks = executionLocks.toMap(),
            )
            runtimeHolder = null
            sessions.clear()
            asyncStates.clear()
            interactiveStates.clear()
            executionLocks.clear()
            pendingNotifications.clear()
            removed
        }
        removed.asyncStates.forEach { (session, state) ->
            state.execJob.cancel()
            state.collectorJob?.cancel()
            unlockIfLocked(removed.executionLocks[session])
        }
        removed.interactiveStates.values.forEach { state ->
            state.collectorJob?.cancel()
        }
        val runtimeClosedCount = removed.holder?.let { holder ->
            runCatching { holder.runtime.closeAll() }.getOrDefault(0)
        } ?: 0
        removed.holder?.scopeJob?.cancel()
        val outcome = TerminalCloseAllOutcome(
            closedCount = maxOf(removed.sessionCount, runtimeClosedCount),
        )
        Logger.i(
            LOG_TAG,
            "close all sessions closedCount=${outcome.closedCount} " +
                    "sessionCount=${removed.sessionCount} runtimeClosed=$runtimeClosedCount"
        )
        return outcome
    }

    /** 释放指定租户/会话内部打开的所有终端会话（不波及其他并发 Agent）。 */
    suspend fun closeTenantSessions(tenantId: String): Int =
        kotlinx.coroutines.withContext(AgentTenantContext(tenantId)) {
            val targetSessions = synchronized(lock) {
                sessions.values.filter { it.tenantId == tenantId }.map { it.handle }
            }
            var count = 0
            for (handle in targetSessions) {
                if (close(handle) is TerminalCloseOutcome.Closed) {
                    count++
                }
            }
            Logger.d(LOG_TAG, "closeTenantSessions tenantId=$tenantId closedCount=$count")
            count
        }

    internal fun installHandleGeneratorForTest(generator: () -> String): AutoCloseable {
        val previous = synchronized(lock) {
            val previous = handleGenerator
            handleGenerator = generator
            previous
        }
        return AutoCloseable {
            synchronized(lock) {
                handleGenerator = previous
            }
        }
    }

    internal fun installRuntimePortFactoryForTest(
        factory: suspend (CoroutineScope) -> TerminalRuntimePort,
    ): AutoCloseable {
        val previous = synchronized(lock) {
            val previous = runtimePortFactory
            runtimePortFactory = factory
            previous
        }
        return AutoCloseable {
            synchronized(lock) {
                runtimePortFactory = previous
            }
        }
    }

    internal fun publicHandleRegexForTest(): Regex = PUBLIC_HANDLE_REGEX

    private suspend fun runtime(): RuntimeHolder {
        synchronized(lock) {
            runtimeHolder?.let { return it }
        }

        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(scopeJob + Dispatchers.IO)
        val created = RuntimeHolder(
            runtime = runtimePortFactory(scope),
            scopeJob = scopeJob,
            scope = scope,
        )
        val existing = synchronized(lock) {
            runtimeHolder ?: created.also { runtimeHolder = it }
        }
        if (existing !== created) {
            created.scopeJob.cancel()
        }
        return existing
    }

    private suspend fun createLibTermRuntimePort(scope: CoroutineScope): TerminalRuntimePort {
        val context = ContextProvider.await().applicationContext
        return LibTermTerminalRuntimePort(
            runtime = LibTerm.runtime(context = context, scope = scope) {},
        )
    }

    private fun generateAvailablePublicHandle(): String {
        repeat(MAX_HANDLE_GENERATION_ATTEMPTS) {
            val candidate = handleGenerator().trim()
            if (!PUBLIC_HANDLE_REGEX.matches(candidate)) {
                return@repeat
            }
            val exists = synchronized(lock) {
                sessions.containsKey(candidate)
            }
            if (!exists) {
                return candidate
            }
        }
        throw IllegalStateException(
            "Unable to allocate terminal session handle after $MAX_HANDLE_GENERATION_ATTEMPTS attempts.",
        )
    }

    private fun randomPublicHandle(): String {
        return Random.Default
            .nextInt(0x10000)
            .toString(16)
            .padStart(PUBLIC_HANDLE_LENGTH, '0')
    }

    private fun mapIdentity(identity: String?): TerminalIdentity {
        return when (identity?.trim()) {
            "user" -> TerminalIdentity.User
            "root" -> TerminalIdentity.Su
            "shizuku" -> TerminalIdentity.Shizuku
            else -> throw IllegalArgumentException("Field 'identity' must be one of user, root, shizuku.")
        }
    }

    private fun currentAsyncId(session: String): String? {
        return synchronized(lock) {
            asyncStates[session]?.asyncId
        }
    }

    private fun startInteractiveCollector(entry: TerminalSessionEntry, holder: RuntimeHolder) {
        val state = InteractiveState(
            inputLock = Mutex(),
            lock = Any(),
        )
        synchronized(lock) {
            interactiveStates[entry.handle] = state
        }
        val collectorJob = holder.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            runCatchingCancellable {
                entry.session.stream.collect { chunk ->
                    // githooks:ignore monitor-lock 采集缓冲由 interactiveState 自己的锁保护，与读取侧同源
                    synchronized(state.lock) {
                        // stdout/stderr 按到达顺序写进同一条流：读取与偏移都只建立在这一条流上。
                        // 分两个缓冲再由上层拼接时，两侧各自增长会让绝对偏移互相平移，哨兵窗口错位。
                        state.text.append(chunk.text)
                        state.outputSequence += 1L
                    }
                }
                // githooks:ignore monitor-lock 同上
                synchronized(state.lock) { state.ended = true }
            }.onFailure {
                // 流异常结束同样视为会话终结（SSH 断连等）
                // githooks:ignore monitor-lock 同上
                synchronized(state.lock) { state.ended = true }
            }
        }
        synchronized(state.lock) {
            state.collectorJob = collectorJob
        }
    }

    /**
     * Invoked by [execJob]'s invokeOnCompletion callback on normal (non-cancelled)
     * completion. Stores the finished result/failure into the session entry so that
     * [readSession] can serve it later, releases the execution Mutex via
     * [completeAsync], and enqueues a notification when notify_on_complete is set.
     */
    private fun onAsyncCompleted(session: String, state: AsyncState) {
        val completionTime = System.currentTimeMillis()
        synchronized(state.lock) {
            val entry = synchronized(lock) { sessions[session] } ?: return
            val elapsed = elapsedSeconds(state.startTimeMs, completionTime)
            state.result?.let { result ->
                entry.completedResult = result
                entry.completedElapsedSeconds = elapsed
            }
            state.failure?.let { failure ->
                entry.completedFailure = failure
                entry.completedElapsedSeconds = elapsed
            }
            state.unexpectedError?.let { error ->
                entry.completedUnexpectedError = error
                entry.completedElapsedSeconds = elapsed
            }
        }
        // Release Mutex + cancel collector + remove asyncState (never blocked on Agent poll).
        completeAsync(session, state)
        // If notify_on_complete, enqueue the notification for the next user message.
        if (state.notifyOnComplete) {
            val entry = synchronized(lock) { sessions[session] } ?: return
            val result = entry.completedResult
            val failure = entry.completedFailure
            val notification = formatCompletionNotification(session, entry, result, failure)
            synchronized(lock) { pendingNotifications.add(notification) }
        }
    }

    private fun formatCompletionNotification(
        sessionId: String,
        entry: TerminalSessionEntry,
        result: CommandResult?,
        failure: TerminalFailure?,
    ): String {
        val prefix = "[IMPORTANT: Background process $sessionId"
        return when {
            result != null -> {
                val exitCode = result.exitCode ?: -1
                val timedOutNote = if (result.timedOut) " (timed out)" else ""
                val output = mergedOutput(result).takeLast(2000)
                "$prefix completed$timedOutNote. Exit code: $exitCode.\nCommand: <background task>\nOutput (last 2000 chars):\n$output]"
            }

            failure != null -> {
                "$prefix failed. Error: ${failure.message}]"
            }

            entry.completedUnexpectedError != null -> {
                "$prefix failed. Error: ${entry.completedUnexpectedError?.message}]"
            }

            else -> "$prefix exited with unknown status.]"
        }
    }

    /**
     * 后台轮询输出统一过滤：截断 + 全量导出（FIXME(async-refactor): 异步
     * 轮询重构时收口此处；DELTA 模式增量导出语义待重新设计）。
     */
    private fun truncateOutput(text: String, maxBytes: Int, exportDir: File?): String {
        return ToolOutputTruncator.filterForAgent(fullContent = text, exportDir = exportDir)
    }

    /** 截断导出目录（filesDir/tool_output）；无 Context（单测）时返回 null（不导出）。 */
    internal var exportDirOverride: File? = null

    private fun exportDir(): File? =
        exportDirOverride ?: ToolOutputTruncator.defaultExportDir()

    private fun mergedOutput(result: CommandResult): String {
        return result.stdoutText() + result.stderrText()
    }

    private fun completeAsync(session: String, state: AsyncState) {
        val executeLock = synchronized(lock) {
            if (asyncStates[session] === state) {
                asyncStates.remove(session)
            }
            executionLocks[session]
        }
        state.collectorJob.cancel()
        unlockIfLocked(executeLock)
    }

    private fun unlockIfLocked(executeLock: Mutex?) {
        if (executeLock?.isLocked == true) {
            runCatching { executeLock.unlock() }
        }
    }

    private fun elapsedSeconds(startTimeMs: Long, nowMs: Long = System.currentTimeMillis()): Long {
        return ((nowMs - startTimeMs).coerceAtLeast(0L) / 1000L)
    }
}

private data class RuntimeHolder(
    val runtime: TerminalRuntimePort,
    val scopeJob: Job,
    val scope: CoroutineScope,
)

/**
 * [TerminalSessionPool.readSession] 的一次性状态快照。五个字段必须原子读取：
 * 分成多次取锁会让“任务刚完成”这个瞬间既看不到完成结果、也看不到运行态。
 */
private data class ReadSnapshot(
    val result: CommandResult?,
    val failure: TerminalFailure?,
    val unexpectedError: Throwable?,
    val elapsedSeconds: Long,
    val asyncState: AsyncState?,
)

internal data class TerminalSessionEntry(
    val handle: String,
    val identity: String,
    val session: TerminalSessionPort,
    val libTermSessionId: String,
    var cwd: String? = null,
    var completedResult: CommandResult? = null,
    var completedFailure: TerminalFailure? = null,
    var completedUnexpectedError: Throwable? = null,
    var completedElapsedSeconds: Long = 0,
    val tenantId: String? = null,
)

internal interface TerminalRuntimePort {
    suspend fun open(
        identity: TerminalIdentity,
        cwd: String?,
        sshOptions: SshOpenOptions?,
    ): OpenResult<TerminalSessionPort>

    suspend fun close(sessionId: String)
    suspend fun closeAll(): Int
}

internal interface TerminalSessionPort {
    val id: String
    val stream: Flow<TerminalTextChunk>

    /** 会话是否仍活着（shell 退出 / SSH 断连后为 false）。默认 true，兼容不感知状态的实现。 */
    val isAlive: Boolean get() = true

    suspend fun exec(command: String, timeoutMillis: Long): TermResult<CommandResult>
    suspend fun write(text: String)
    suspend fun close()
}

private class LibTermTerminalRuntimePort(
    private val runtime: LibTermRuntime,
) : TerminalRuntimePort {
    override suspend fun open(
        identity: TerminalIdentity,
        cwd: String?,
        sshOptions: SshOpenOptions?,
    ): OpenResult<TerminalSessionPort> {
        return when (val result = runtime.open {
            this.identity = identity
            this.cwd = cwd
            if (sshOptions != null) {
                ssh {
                    host = sshOptions.host
                    port = sshOptions.port
                    username = sshOptions.username
                    hostKeyPolicy = sshOptions.hostKeyPolicy
                    connectTimeoutMillis = sshOptions.connectTimeoutMillis
                    serverAliveIntervalMillis = sshOptions.serverAliveIntervalMillis
                    when (val auth = sshOptions.auth) {
                        is SshAuth.Password -> password(auth.value)
                        is SshAuth.PrivateKey -> {
                            throw IllegalArgumentException("Private key authentication is not supported yet")
                        }
                    }
                }
            }
        }) {
            is OpenResult.Success -> OpenResult.Success(LibTermTerminalSessionPort(result.value))
            is OpenResult.Failure -> OpenResult.Failure(result.failure)
        }
    }

    override suspend fun close(sessionId: String) {
        runtime.close(sessionId)
    }

    override suspend fun closeAll(): Int {
        return runtime.closeAll()
    }
}

private class LibTermTerminalSessionPort(
    private val delegate: LibTermSession,
) : TerminalSessionPort {
    override val id: String
        get() = delegate.id

    override val isAlive: Boolean
        get() = delegate.state.value is SessionState.Running || delegate.state.value is SessionState.Starting

    override val stream: Flow<TerminalTextChunk>
        get() = delegate.stream

    override suspend fun exec(command: String, timeoutMillis: Long): TermResult<CommandResult> {
        return delegate.exec(command = command, timeoutMillis = timeoutMillis)
    }

    override suspend fun write(text: String) {
        delegate.write(text)
    }

    override suspend fun close() {
        delegate.close()
    }
}

private data class AsyncState(
    val asyncId: String,
    val execJob: Job,
    val collectorJob: Job,
    val startTimeMs: Long,
    val stdoutPartial: StringBuilder,
    val stderrPartial: StringBuilder,
    val lock: Any,
    val notifyOnComplete: Boolean = false,
    var result: CommandResult? = null,
    var failure: TerminalFailure? = null,
    var unexpectedError: Throwable? = null,
    var stdoutDeltaOffset: Int = 0,
    var stderrDeltaOffset: Int = 0,
)

private data class InteractiveState(
    val inputLock: Mutex,
    val lock: Any,
    /** 到达序合并输出流。 */
    val text: StringBuilder = StringBuilder(),
    val writeResults: MutableMap<String, InteractiveWriteResult> = linkedMapOf(),
    var collectorJob: Job? = null,
    /** 输出流已完结（会话退出/断连）。 */
    var ended: Boolean = false,
    var deltaOffset: Int = 0,
    var inputSequence: Long = 0L,
    var outputSequence: Long = 0L,
)

private data class InteractiveWriteResult(
    val bytesWritten: Int,
    val sequence: Long,
)

private data class InteractiveReadSnapshot(
    val text: String,
    val sequence: Long,
    val truncated: Boolean,
)

sealed interface TerminalOpenOutcome {
    data class Success(val session: String, val identity: String) : TerminalOpenOutcome
    data class Failure(val failure: TerminalFailure, val elapsedSeconds: Long) : TerminalOpenOutcome
    data class InvalidRequest(val message: String) : TerminalOpenOutcome
}

sealed interface TerminalCommandOutcome {
    data class Success(
        val session: String,
        val identity: String,
        val result: CommandResult,
        val elapsedSeconds: Long,
    ) : TerminalCommandOutcome

    data class Timeout(
        val session: String,
        val identity: String,
        val result: CommandResult,
        val elapsedSeconds: Long,
    ) : TerminalCommandOutcome

    data class Failure(
        val session: String?,
        val identity: String?,
        val failure: TerminalFailure,
        val elapsedSeconds: Long,
    ) : TerminalCommandOutcome

    data class SessionNotFound(val session: String) : TerminalCommandOutcome
    data class Busy(val session: String, val asyncId: String?) : TerminalCommandOutcome
    data class UnexpectedError(
        val session: String?,
        val throwable: Throwable,
        val elapsedSeconds: Long,
    ) : TerminalCommandOutcome
}

sealed interface TerminalAsyncStartOutcome {
    data class Accepted(val asyncId: String, val elapsedSeconds: Long) : TerminalAsyncStartOutcome
    data class SessionNotFound(val session: String) : TerminalAsyncStartOutcome
    data class Busy(val session: String, val asyncId: String?) : TerminalAsyncStartOutcome
    data class InvalidRequest(val message: String) : TerminalAsyncStartOutcome
}

sealed interface TerminalCloseOutcome {
    data object Closed : TerminalCloseOutcome
    data class UnexpectedError(val throwable: Throwable) : TerminalCloseOutcome
}

enum class TerminalReadMode(val wireName: String) {
    DELTA("delta"),
    SNAPSHOT("snapshot"),
}

sealed interface TerminalInteractiveWriteOutcome {
    data class Accepted(
        val bytesWritten: Int,
        val sequence: Long,
        val replayed: Boolean,
    ) : TerminalInteractiveWriteOutcome

    data class SessionNotFound(val session: String) : TerminalInteractiveWriteOutcome
    data class NotInteractive(val session: String) : TerminalInteractiveWriteOutcome
    data class Busy(val session: String) : TerminalInteractiveWriteOutcome
    data class UnexpectedError(val throwable: Throwable) : TerminalInteractiveWriteOutcome
}

sealed interface TerminalInteractiveReadOutcome {
    data class Success(
        val text: String,
        val mode: TerminalReadMode,
        val sequence: Long,
        val truncated: Boolean,
    ) : TerminalInteractiveReadOutcome

    data class SessionNotFound(val session: String) : TerminalInteractiveReadOutcome
    data class NotInteractive(val session: String) : TerminalInteractiveReadOutcome
}

sealed interface TerminalReadOutcome {
    data class Running(
        val session: String,
        val output: String,
        val elapsedSeconds: Long,
    ) : TerminalReadOutcome

    data class Exited(
        val session: String,
        val output: String,
        val exitCode: Int,
        val elapsedSeconds: Long,
    ) : TerminalReadOutcome

    data class TimedOut(
        val session: String,
        val output: String,
        val elapsedSeconds: Long,
    ) : TerminalReadOutcome

    data class Crashed(
        val session: String,
        val errorMessage: String,
        val elapsedSeconds: Long,
    ) : TerminalReadOutcome

    data class SessionNotFound(val session: String) : TerminalReadOutcome
    data class NotBackground(val session: String) : TerminalReadOutcome
}

data class TerminalCloseAllOutcome(
    val closedCount: Int,
)

private data class RemovedSession(
    val entry: TerminalSessionEntry?,
    val asyncState: AsyncState?,
    val interactiveState: InteractiveState?,
    val executionLock: Mutex?,
)

private data class RemovedAll(
    val holder: RuntimeHolder?,
    val sessionCount: Int,
    val asyncStates: Map<String, AsyncState>,
    val interactiveStates: Map<String, InteractiveState>,
    val executionLocks: Map<String, Mutex>,
)
