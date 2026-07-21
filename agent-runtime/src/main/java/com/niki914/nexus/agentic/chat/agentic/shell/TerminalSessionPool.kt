package com.niki914.nexus.agentic.chat.agentic.shell

import com.niki914.libterm.OpenResult
import com.niki914.libterm.OutputStream
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
import com.niki914.nexus.xposed.api.util.ContextProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.util.UUID
import kotlin.random.Random

object TerminalSessionPool {
    private const val CUSTOM_TOOL_SESSION = "__custom_user"
    private const val SSH_PUBLIC_IDENTITY = "ssh"
    private const val PUBLIC_HANDLE_LENGTH = 4
    private const val MAX_HANDLE_GENERATION_ATTEMPTS = 64
    private const val DEFAULT_INTERACTIVE_READ_MAX_BYTES = 8192
    private const val GENERATED_HANDLE_COLLISION_MESSAGE = "Generated session handle collision."
    private val PUBLIC_HANDLE_REGEX: Regex = Regex("^[0-9a-f]{4}$")
    private val lock = Any()
    private var runtimeHolder: RuntimeHolder? = null
    private val sessions: MutableMap<String, TerminalSessionEntry> = linkedMapOf()
    private val asyncStates: MutableMap<String, AsyncState> = linkedMapOf()
    private val interactiveStates: MutableMap<String, InteractiveState> = linkedMapOf()
    private val executionLocks: MutableMap<String, Mutex> = linkedMapOf()
    private var handleGenerator: () -> String = ::randomPublicHandle
    private var runtimePortFactory: suspend (CoroutineScope) -> TerminalRuntimePort =
        ::createLibTermRuntimePort

    suspend fun open(identity: String, cwd: String? = null): TerminalOpenOutcome {
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
            collectOutput = false,
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
                val entry = TerminalSessionEntry(
                    handle = handle,
                    identity = publicIdentity,
                    session = result.value,
                    libTermSessionId = result.value.id,
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
                    TerminalOpenOutcome.Success(
                        session = entry.handle,
                        identity = entry.identity,
                    )
                }
            }

            is OpenResult.Failure -> TerminalOpenOutcome.Failure(
                failure = result.failure,
                elapsedSeconds = elapsedSeconds(startTimeMs),
            )
        }
    }

    suspend fun writeInteractive(
        session: String,
        text: String,
        requestId: String? = null,
    ): TerminalInteractiveWriteOutcome {
        val (entry, state) = synchronized(lock) {
            val entry =
                sessions[session] ?: return TerminalInteractiveWriteOutcome.SessionNotFound(session)
            val state =
                interactiveStates[session] ?: return TerminalInteractiveWriteOutcome.NotInteractive(
                    session
                )
            entry to state
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

        return try {
            if (!state.inputLock.tryLock()) {
                return TerminalInteractiveWriteOutcome.Busy(session)
            }
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
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            TerminalInteractiveWriteOutcome.UnexpectedError(error)
        }
    }

    fun readInteractive(
        session: String,
        mode: TerminalInteractiveReadMode = TerminalInteractiveReadMode.DELTA,
        maxBytes: Int = DEFAULT_INTERACTIVE_READ_MAX_BYTES,
    ): TerminalInteractiveReadOutcome {
        val state = synchronized(lock) {
            if (!sessions.containsKey(session)) {
                return TerminalInteractiveReadOutcome.SessionNotFound(session)
            }
            interactiveStates[session] ?: return TerminalInteractiveReadOutcome.NotInteractive(
                session
            )
        }
        val maxChars = maxBytes.coerceAtLeast(1)
        val snapshot = synchronized(state.lock) {
            val rawStdout = when (mode) {
                TerminalInteractiveReadMode.DELTA -> state.stdout.substring(state.stdoutDeltaOffset)
                TerminalInteractiveReadMode.SNAPSHOT -> state.stdout.toString()
            }
            val rawStderr = when (mode) {
                TerminalInteractiveReadMode.DELTA -> state.stderr.substring(state.stderrDeltaOffset)
                TerminalInteractiveReadMode.SNAPSHOT -> state.stderr.toString()
            }
            if (mode == TerminalInteractiveReadMode.DELTA) {
                state.stdoutDeltaOffset = state.stdout.length
                state.stderrDeltaOffset = state.stderr.length
            }
            InteractiveReadSnapshot(
                stdout = rawStdout.takeLast(maxChars),
                stderr = rawStderr.takeLast(maxChars),
                sequence = state.outputSequence,
                truncated = rawStdout.length > maxChars || rawStderr.length > maxChars,
            )
        }
        return TerminalInteractiveReadOutcome.Success(
            stdout = snapshot.stdout,
            stderr = snapshot.stderr,
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

    suspend fun executeBlocking(
        session: String,
        command: String,
        timeoutMs: Long,
    ): TerminalCommandOutcome {
        val startTimeMs = System.currentTimeMillis()
        val (entry, executeLock) = synchronized(lock) {
            val entry = sessions[session] ?: return TerminalCommandOutcome.SessionNotFound(session)
            val executeLock = executionLocks.getOrPut(session) { Mutex() }
            entry to executeLock
        }
        if (!executeLock.tryLock()) {
            return TerminalCommandOutcome.Busy(
                session = session,
                asyncId = currentAsyncId(session),
            )
        }

        return try {
            when (val result = entry.session.exec(command = command, timeoutMillis = timeoutMs)) {
                is TermResult.Success -> {
                    if (result.value.timedOut) {
                        TerminalCommandOutcome.Timeout(
                            session = entry.handle,
                            identity = entry.identity,
                            result = result.value,
                            elapsedSeconds = elapsedSeconds(startTimeMs),
                        )
                    } else {
                        TerminalCommandOutcome.Success(
                            session = entry.handle,
                            identity = entry.identity,
                            result = result.value,
                            elapsedSeconds = elapsedSeconds(startTimeMs),
                        )
                    }
                }

                is TermResult.Failure -> TerminalCommandOutcome.Failure(
                    session = entry.handle,
                    identity = entry.identity,
                    failure = result.failure,
                    elapsedSeconds = elapsedSeconds(startTimeMs),
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            TerminalCommandOutcome.UnexpectedError(
                throwable = error,
                elapsedSeconds = elapsedSeconds(startTimeMs),
            )
        } finally {
            executeLock.unlock()
        }
    }

    suspend fun startAsync(
        session: String,
        command: String,
        timeoutMs: Long,
    ): TerminalAsyncStartOutcome {
        val startTimeMs = System.currentTimeMillis()
        val (entry, executeLock, holder) = synchronized(lock) {
            val entry =
                sessions[session] ?: return TerminalAsyncStartOutcome.SessionNotFound(session)
            val executeLock = executionLocks.getOrPut(session) { Mutex() }
            val holder = runtimeHolder ?: return TerminalAsyncStartOutcome.InvalidRequest(
                "Terminal runtime is not initialized. Use open or open_and_exec first.",
            )
            Triple(entry, executeLock, holder)
        }
        if (!executeLock.tryLock()) {
            return TerminalAsyncStartOutcome.Busy(
                session = session,
                asyncId = currentAsyncId(session),
            )
        }

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
            try {
                when (val result =
                    entry.session.exec(command = command, timeoutMillis = timeoutMs)) {
                    is TermResult.Success -> synchronized(state.lock) {
                        state.result = result.value
                    }

                    is TermResult.Failure -> synchronized(state.lock) {
                        state.failure = result.failure
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
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
        )
        synchronized(lock) {
            asyncStates[session] = state
        }
        execJob.start()

        return TerminalAsyncStartOutcome.Accepted(
            asyncId = asyncId,
            elapsedSeconds = elapsedSeconds(startTimeMs),
        )
    }

    suspend fun readAsyncResult(session: String, asyncId: String): TerminalAsyncReadOutcome {
        val state = synchronized(lock) {
            if (!sessions.containsKey(session)) {
                return TerminalAsyncReadOutcome.SessionNotFound(session)
            }
            val state = asyncStates[session]
                ?: return TerminalAsyncReadOutcome.AsyncNotFound(
                    session = session,
                    asyncId = asyncId
                )
            if (state.asyncId != asyncId) {
                return TerminalAsyncReadOutcome.AsyncNotFound(session = session, asyncId = asyncId)
            }
            state
        }

        val snapshot = synchronized(state.lock) {
            AsyncSnapshot(
                stdoutPartial = state.stdoutPartial.toString(),
                stderrPartial = state.stderrPartial.toString(),
                result = state.result,
                failure = state.failure,
                unexpectedError = state.unexpectedError,
                execCompleted = state.execJob.isCompleted,
            )
        }
        val elapsedSeconds = elapsedSeconds(state.startTimeMs)
        snapshot.result?.let { result ->
            completeAsync(session = session, state = state)
            return if (result.timedOut) {
                TerminalAsyncReadOutcome.TimedOut(
                    result = result,
                    elapsedSeconds = elapsedSeconds,
                )
            } else {
                TerminalAsyncReadOutcome.Completed(
                    result = result,
                    elapsedSeconds = elapsedSeconds,
                )
            }
        }
        snapshot.failure?.let { failure ->
            completeAsync(session = session, state = state)
            return TerminalAsyncReadOutcome.Failure(
                failure = failure,
                elapsedSeconds = elapsedSeconds,
            )
        }
        snapshot.unexpectedError?.let { error ->
            completeAsync(session = session, state = state)
            return TerminalAsyncReadOutcome.UnexpectedError(
                throwable = error,
                elapsedSeconds = elapsedSeconds,
            )
        }
        if (snapshot.execCompleted) {
            completeAsync(session = session, state = state)
            return TerminalAsyncReadOutcome.UnexpectedError(
                throwable = IllegalStateException("Async command completed without result."),
                elapsedSeconds = elapsedSeconds,
            )
        }
        return TerminalAsyncReadOutcome.Running(
            stdoutPartial = snapshot.stdoutPartial,
            stderrPartial = snapshot.stderrPartial,
            elapsedSeconds = elapsedSeconds,
        )
    }

    suspend fun close(session: String): TerminalCloseOutcome {
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
        return try {
            entry.session.close()
            TerminalCloseOutcome.Closed
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
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
        return TerminalCloseAllOutcome(
            closedCount = maxOf(removed.sessionCount, runtimeClosedCount),
        )
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
            entry.session.stream.collect { chunk ->
                synchronized(state.lock) {
                    when (chunk.stream) {
                        OutputStream.STDOUT -> state.stdout.append(chunk.text)
                        OutputStream.STDERR -> state.stderr.append(chunk.text)
                    }
                    state.outputSequence += 1L
                }
            }
        }
        synchronized(state.lock) {
            state.collectorJob = collectorJob
        }
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

internal data class TerminalSessionEntry(
    val handle: String,
    val identity: String,
    val session: TerminalSessionPort,
    val libTermSessionId: String,
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
    var result: CommandResult? = null,
    var failure: TerminalFailure? = null,
    var unexpectedError: Throwable? = null,
)

private data class InteractiveState(
    val inputLock: Mutex,
    val lock: Any,
    val stdout: StringBuilder = StringBuilder(),
    val stderr: StringBuilder = StringBuilder(),
    val writeResults: MutableMap<String, InteractiveWriteResult> = linkedMapOf(),
    var collectorJob: Job? = null,
    var stdoutDeltaOffset: Int = 0,
    var stderrDeltaOffset: Int = 0,
    var inputSequence: Long = 0L,
    var outputSequence: Long = 0L,
)

private data class InteractiveWriteResult(
    val bytesWritten: Int,
    val sequence: Long,
)

private data class InteractiveReadSnapshot(
    val stdout: String,
    val stderr: String,
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
    data class UnexpectedError(val throwable: Throwable, val elapsedSeconds: Long) :
        TerminalCommandOutcome
}

sealed interface TerminalAsyncStartOutcome {
    data class Accepted(val asyncId: String, val elapsedSeconds: Long) : TerminalAsyncStartOutcome
    data class SessionNotFound(val session: String) : TerminalAsyncStartOutcome
    data class Busy(val session: String, val asyncId: String?) : TerminalAsyncStartOutcome
    data class InvalidRequest(val message: String) : TerminalAsyncStartOutcome
}

sealed interface TerminalAsyncReadOutcome {
    data class Running(
        val stdoutPartial: String,
        val stderrPartial: String,
        val elapsedSeconds: Long,
    ) : TerminalAsyncReadOutcome

    data class Completed(val result: CommandResult, val elapsedSeconds: Long) :
        TerminalAsyncReadOutcome

    data class TimedOut(val result: CommandResult, val elapsedSeconds: Long) :
        TerminalAsyncReadOutcome

    data class Failure(val failure: TerminalFailure, val elapsedSeconds: Long) :
        TerminalAsyncReadOutcome

    data class AsyncNotFound(val session: String, val asyncId: String) : TerminalAsyncReadOutcome
    data class SessionNotFound(val session: String) : TerminalAsyncReadOutcome
    data class UnexpectedError(val throwable: Throwable, val elapsedSeconds: Long) :
        TerminalAsyncReadOutcome
}

sealed interface TerminalCloseOutcome {
    data object Closed : TerminalCloseOutcome
    data class UnexpectedError(val throwable: Throwable) : TerminalCloseOutcome
}

enum class TerminalInteractiveReadMode(val wireName: String) {
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
        val stdout: String,
        val stderr: String,
        val mode: TerminalInteractiveReadMode,
        val sequence: Long,
        val truncated: Boolean,
    ) : TerminalInteractiveReadOutcome

    data class SessionNotFound(val session: String) : TerminalInteractiveReadOutcome
    data class NotInteractive(val session: String) : TerminalInteractiveReadOutcome
}

data class TerminalCloseAllOutcome(
    val closedCount: Int,
)

private data class AsyncSnapshot(
    val stdoutPartial: String,
    val stderrPartial: String,
    val result: CommandResult?,
    val failure: TerminalFailure?,
    val unexpectedError: Throwable?,
    val execCompleted: Boolean,
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
