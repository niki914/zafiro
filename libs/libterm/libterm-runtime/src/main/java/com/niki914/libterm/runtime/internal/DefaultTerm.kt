package com.niki914.libterm.runtime.internal

import com.niki914.libterm.AuthorizationMode
import com.niki914.libterm.OpenResult
import com.niki914.libterm.SessionState
import com.niki914.libterm.TerminalIdentity
import com.niki914.libterm.TerminalManager
import com.niki914.libterm.TerminalOpenOptions
import com.niki914.libterm.runtime.CommandResult
import com.niki914.libterm.runtime.LibTermRuntime
import com.niki914.libterm.runtime.LibTermSession
import com.niki914.libterm.runtime.Term
import com.niki914.libterm.runtime.TermResult
import com.niki914.libterm.runtime.TerminalTextChunk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@OptIn(ExperimentalCoroutinesApi::class)
internal class DefaultTerm(
    private val runtime: LibTermRuntime,
    private val identity: TerminalIdentity,
    private val authorizationMode: AuthorizationMode,
    private val openOptions: TerminalOpenOptions,
    private val scope: CoroutineScope,
    private val ownsScope: Boolean,
) : Term {
    internal constructor(
        manager: TerminalManager,
        identity: TerminalIdentity,
        scope: CoroutineScope,
        ownsScope: Boolean,
    ) : this(
        runtime = LibTermRuntime(manager = manager),
        identity = identity,
        authorizationMode = AuthorizationMode.REQUEST_IF_NEEDED,
        openOptions = TerminalOpenOptions(),
        scope = scope,
        ownsScope = ownsScope,
    )

    private val lifecycleMutex = Mutex()
    private val stateEvents = MutableStateFlow<SessionState>(SessionState.Closed)
    private val outputEvents = MutableSharedFlow<TerminalTextChunk>(
        replay = OUTPUT_REPLAY_CAPACITY,
        extraBufferCapacity = 64,
    )

    private var session: LibTermSession? = null
    private var stateJob: Job? = null
    private var outputJob: Job? = null

    override val state: StateFlow<SessionState> = stateEvents.asStateFlow()
    override val stream: Flow<TerminalTextChunk> = outputEvents.asSharedFlow()

    override suspend fun open(): TermResult<Unit> {
        return when (val ensured = ensureSession()) {
            is TermResult.Success -> TermResult.Success(Unit)
            is TermResult.Failure -> TermResult.Failure(ensured.failure)
        }
    }

    override suspend fun exec(
        command: String,
        timeoutMillis: Long,
    ): TermResult<CommandResult> {
        val activeSession = when (val ensured = ensureSession()) {
            is TermResult.Success -> ensured.value
            is TermResult.Failure -> return TermResult.Failure(ensured.failure)
        }
        return activeSession.exec(
            command = command,
            timeoutMillis = timeoutMillis,
        )
    }

    override suspend fun write(text: String): TermResult<Unit> {
        return write(text.encodeToByteArray())
    }

    override suspend fun write(bytes: ByteArray): TermResult<Unit> {
        val activeSession = when (val ensured = ensureSession()) {
            is TermResult.Success -> ensured.value
            is TermResult.Failure -> return TermResult.Failure(ensured.failure)
        }
        return activeSession.write(bytes)
    }

    override suspend fun close(): TermResult<Unit> {
        val existing = lifecycleMutex.withLock { session }
        existing?.close()
        clearSession(existing)
        if (ownsScope) {
            scope.cancel()
        }
        stateEvents.value = SessionState.Closed
        return TermResult.Success(Unit)
    }

    private suspend fun ensureSession(): TermResult<LibTermSession> {
        return lifecycleMutex.withLock {
            val existing = session
            if (existing != null && existing.state.value == SessionState.Running) {
                return@withLock TermResult.Success(existing)
            }
            if (existing != null) {
                existing.close()
                clearSessionLocked(existing)
            }

            when (val opened = runtime.open {
                identity = this@DefaultTerm.identity
                authorizationMode = this@DefaultTerm.authorizationMode
                cwd = this@DefaultTerm.openOptions.cwd
                sshOptions = this@DefaultTerm.openOptions.ssh
            }) {
                is OpenResult.Success -> {
                    bindSessionLocked(opened.value)
                    TermResult.Success(opened.value)
                }

                is OpenResult.Failure -> {
                    stateEvents.value = SessionState.Failed(opened.failure)
                    TermResult.Failure(opened.failure)
                }
            }
        }
    }

    private suspend fun bindSession(newSession: LibTermSession) {
        lifecycleMutex.withLock {
            bindSessionLocked(newSession)
        }
    }

    private suspend fun clearSession(existing: LibTermSession?) {
        lifecycleMutex.withLock {
            clearSessionLocked(existing)
        }
    }

    private fun bindSessionLocked(newSession: LibTermSession) {
        session = newSession
        stateEvents.value = newSession.state.value
        outputEvents.resetReplayCache()
        newSession.latest(Int.MAX_VALUE).forEach(outputEvents::tryEmit)
        stateJob?.cancel()
        outputJob?.cancel()
        stateJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            newSession.state.collect { newState ->
                stateEvents.value = newState
            }
        }
        outputJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            newSession.stream.collect { chunk ->
                outputEvents.emit(chunk)
            }
        }
    }

    private fun clearSessionLocked(existing: LibTermSession?) {
        if (existing == null || session == existing) {
            session = null
        }
        outputEvents.resetReplayCache()
        stateJob?.cancel()
        stateJob = null
        outputJob?.cancel()
        outputJob = null
    }

    private companion object {
        const val OUTPUT_REPLAY_CAPACITY: Int = 64
    }
}
