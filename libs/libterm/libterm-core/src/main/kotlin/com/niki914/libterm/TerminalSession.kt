package com.niki914.libterm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class TerminalSession(
    val id: String,
    private val backend: TerminalBackend,
    private val clock: Clock,
    private val bufferConfig: TerminalBufferConfig = TerminalBufferConfig(),
    private val scope: CoroutineScope,
) {
    val identity: TerminalIdentity = backend.identity

    private val lifecycleLock = Any()
    private val bufferLock = Any()
    private val buffer = mutableListOf<OutputChunk>()

    private val _state = MutableStateFlow<SessionState>(SessionState.Closed)
    private val outputEvents = MutableSharedFlow<OutputChunk>(
        replay = 0,
        extraBufferCapacity = 64,
    )

    private var totalBufferedBytes: Int = 0
    private var startDeferred: CompletableDeferred<SessionState>? = null
    private var closeDeferred: CompletableDeferred<SessionState>? = null
    private var outputCollectionJob: Job? = null
    private var exitAwaitJob: Job? = null
    private var startAttempted: Boolean = false
    private var closeRequested: Boolean = false
    private var closeInvoked: Boolean = false

    val state: StateFlow<SessionState> = _state.asStateFlow()
    val output: Flow<OutputChunk> = outputEvents.asSharedFlow()

    val currentState: SessionState
        get() = state.value

    suspend fun start(
        openOptions: TerminalOpenOptions = TerminalOpenOptions(),
    ): SessionState {
        val pendingStart = synchronized(lifecycleLock) {
            startDeferred?.let { return@synchronized it }

            if (startAttempted) {
                return@synchronized completedDeferred(_state.value)
            }

            startAttempted = true
            _state.value = SessionState.Starting
            CompletableDeferred<SessionState>().also { startDeferred = it }
        }

        if (pendingStart.isCompleted) {
            return pendingStart.await()
        }

        ensureOutputCollectionStarted()
        val startResult = try {
            backend.start(openOptions)
        } catch (error: Throwable) {
            if (error is CancellationException) {
                throw error
            }
            BackendStartResult.Failed(
                failure = TerminalFailure.StartupFailed(
                    identity = identity,
                    message = error.message,
                    cause = error,
                ),
            )
        }

        var closeToAwait: CompletableDeferred<SessionState>? = null
        var shouldInvokeCloseAfterStart = false
        var shouldLaunchBackgroundJobs = false
        val resolvedState: SessionState? = synchronized(lifecycleLock) {
            if (closeRequested) {
                return@synchronized when (startResult) {
                    BackendStartResult.Started -> {
                        closeToAwait = closeDeferred
                        shouldLaunchBackgroundJobs = true
                        shouldInvokeCloseAfterStart = true
                        null
                    }

                    is BackendStartResult.Failed -> {
                        val failedState = SessionState.Failed(startResult.failure)
                        _state.value = failedState
                        completeIfNeeded(startDeferred, failedState)
                        completeIfNeeded(closeDeferred, failedState)
                        closeToAwait = null
                        failedState
                    }
                }
            }

            closeToAwait = null
            val nextState = when (val existingState = _state.value) {
                is SessionState.Failed -> existingState
                SessionState.Closed -> existingState
                SessionState.Running -> existingState
                SessionState.Starting -> when (startResult) {
                    BackendStartResult.Started -> {
                        shouldLaunchBackgroundJobs = true
                        SessionState.Running
                    }

                    is BackendStartResult.Failed -> SessionState.Failed(startResult.failure)
                }
            }

            _state.value = nextState
            completeIfNeeded(startDeferred, nextState)
            if (nextState != SessionState.Running) {
                completeIfNeeded(closeDeferred, nextState)
            }
            nextState
        }

        if (shouldLaunchBackgroundJobs) {
            ensureExitAwaitStarted()
        } else if (startResult is BackendStartResult.Failed) {
            cancelOutputCollection()
        }
        if (shouldInvokeCloseAfterStart) {
            invokeCloseIfNeeded()
        }
        closeToAwait?.let { return it.await() }
        return requireNotNull(resolvedState)
    }

    fun latest(limit: Int): List<OutputChunk> {
        if (limit <= 0) {
            return emptyList()
        }

        return synchronized(bufferLock) {
            buffer.takeLast(limit).toList()
        }
    }

    suspend fun send(input: TerminalBytes): SendResult {
        if (currentState != SessionState.Running) {
            return SendResult.Failed(
                TerminalFailure.AlreadyClosed(
                    identity = identity,
                    message = "Session is not running",
                ),
            )
        }

        return try {
            backend.send(input).also { result ->
                val failure = (result as? SendResult.Failed)?.failure
                if (failure is TerminalFailure.RuntimeTerminated) {
                    applyOutputFailure(failure)
                }
            }
        } catch (error: Throwable) {
            if (error is CancellationException) {
                throw error
            }
            val failure = runtimeFailure(error)
            applyOutputFailure(failure)
            SendResult.Failed(failure)
        }
    }

    suspend fun send(bytes: ByteArray): SendResult {
        return send(TerminalBytes.of(bytes))
    }

    suspend fun close(): SessionState {
        val immediateState = synchronized(lifecycleLock) {
            if (!startAttempted && _state.value == SessionState.Closed) {
                return@synchronized SessionState.Closed
            }
            null
        }
        if (immediateState != null) {
            return immediateState
        }

        var shouldInvokeClose = false
        val pendingClose = synchronized(lifecycleLock) {
            closeDeferred?.let { return@synchronized it }

            closeRequested = true
            startAttempted = true
            when (val existingState = _state.value) {
                is SessionState.Failed -> {
                    return@synchronized completedAndRememberClose(existingState)
                }

                SessionState.Closed -> {
                    if (startDeferred != null) {
                        return@synchronized completedAndRememberClose(existingState)
                    }
                    return@synchronized completedAndRememberClose(existingState)
                }

                SessionState.Running -> {
                    shouldInvokeClose = true
                }

                SessionState.Starting -> Unit
            }

            CompletableDeferred<SessionState>().also { closeDeferred = it }
        }

        if (pendingClose.isCompleted) {
            return pendingClose.await()
        }

        if (shouldInvokeClose) {
            invokeCloseIfNeeded()
        }
        synchronized(lifecycleLock) {
            when (val existingState = _state.value) {
                is SessionState.Failed -> completeIfNeeded(pendingClose, existingState)
                SessionState.Closed -> completeIfNeeded(pendingClose, existingState)
                SessionState.Running,
                SessionState.Starting,
                    -> Unit
            }
        }
        return pendingClose.await()
    }

    private fun ensureOutputCollectionStarted() {
        synchronized(lifecycleLock) {
            if (outputCollectionJob == null) {
                outputCollectionJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        backend.output.collect { chunk ->
                            appendChunk(chunk)
                            outputEvents.emit(chunk)
                        }
                    } catch (error: Throwable) {
                        if (error is CancellationException) {
                            throw error
                        }
                        applyOutputFailure(runtimeFailure(error), cancelOutputJob = false)
                    }
                }
            }
        }
    }

    private fun ensureExitAwaitStarted() {
        synchronized(lifecycleLock) {
            if (exitAwaitJob == null) {
                exitAwaitJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    val exitFailure = awaitExitSafely()
                    applyExitResult(exitFailure)
                }
            }
        }
    }

    private fun cancelOutputCollection() {
        synchronized(lifecycleLock) {
            outputCollectionJob?.cancel()
            outputCollectionJob = null
        }
    }

    private fun appendChunk(chunk: OutputChunk) {
        synchronized(bufferLock) {
            buffer += chunk
            totalBufferedBytes += chunk.bytes.size
            trimBufferLocked()
        }
    }

    private fun trimBufferLocked() {
        while (buffer.size > bufferConfig.maxChunkCount ||
            (totalBufferedBytes > bufferConfig.maxByteCount && buffer.size > 1)
        ) {
            val removed = buffer.removeAt(0)
            totalBufferedBytes -= removed.bytes.size
        }
    }

    private fun applyExitResult(exitFailure: TerminalFailure?): SessionState {
        return synchronized(lifecycleLock) {
            val resolvedState = when (val existingState = _state.value) {
                is SessionState.Failed -> existingState
                SessionState.Closed,
                SessionState.Starting,
                SessionState.Running,
                    -> exitFailure?.let(SessionState::Failed) ?: SessionState.Closed
            }

            _state.value = resolvedState
            outputCollectionJob?.cancel()
            outputCollectionJob = null
            completeIfNeeded(startDeferred, resolvedState)
            completeIfNeeded(closeDeferred, resolvedState)
            resolvedState
        }
    }

    private fun applyOutputFailure(
        failure: TerminalFailure,
        cancelOutputJob: Boolean = true,
    ): SessionState {
        return synchronized(lifecycleLock) {
            val resolvedState = when (val existingState = _state.value) {
                is SessionState.Failed -> existingState
                SessionState.Closed -> existingState
                SessionState.Starting,
                SessionState.Running,
                    -> SessionState.Failed(failure)
            }

            _state.value = resolvedState
            if (cancelOutputJob) {
                outputCollectionJob?.cancel()
            }
            outputCollectionJob = null
            exitAwaitJob?.cancel()
            exitAwaitJob = null
            completeIfNeeded(startDeferred, resolvedState)
            completeIfNeeded(closeDeferred, resolvedState)
            resolvedState
        }
    }

    private suspend fun awaitExitSafely(): TerminalFailure? {
        return try {
            backend.awaitExit()
        } catch (error: Throwable) {
            if (error is CancellationException) {
                throw error
            }
            runtimeFailure(error)
        }
    }

    private suspend fun invokeCloseIfNeeded() {
        val shouldClose = synchronized(lifecycleLock) {
            if (closeInvoked) {
                false
            } else {
                closeInvoked = true
                true
            }
        }
        if (!shouldClose) {
            return
        }

        try {
            backend.close()
        } catch (error: Throwable) {
            if (error is CancellationException) {
                throw error
            }
            applyExitResult(runtimeFailure(error))
        }
    }

    private fun runtimeFailure(error: Throwable): TerminalFailure.RuntimeTerminated {
        return TerminalFailure.RuntimeTerminated(
            identity = identity,
            message = error.message,
            cause = error,
        )
    }

    private fun completedAndRememberClose(state: SessionState): CompletableDeferred<SessionState> {
        return completedDeferred(state).also { deferred ->
            closeDeferred = deferred
        }
    }

    private fun completeIfNeeded(
        deferred: CompletableDeferred<SessionState>?,
        state: SessionState,
    ) {
        if (deferred != null && !deferred.isCompleted) {
            deferred.complete(state)
        }
    }

    private fun completedDeferred(state: SessionState): CompletableDeferred<SessionState> {
        return CompletableDeferred<SessionState>().also { deferred ->
            deferred.complete(state)
        }
    }
}
