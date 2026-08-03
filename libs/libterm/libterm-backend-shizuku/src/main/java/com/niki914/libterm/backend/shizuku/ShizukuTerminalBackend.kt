package com.niki914.libterm.backend.shizuku

import android.content.Context
import com.niki914.libterm.BackendStartResult
import com.niki914.libterm.Clock
import com.niki914.libterm.OutputChunk
import com.niki914.libterm.OutputStream
import com.niki914.libterm.SendResult
import com.niki914.libterm.TerminalBackend
import com.niki914.libterm.TerminalBytes
import com.niki914.libterm.TerminalFailure
import com.niki914.libterm.TerminalIdentity
import com.niki914.libterm.TerminalOpenOptions
import com.niki914.libterm.backend.shizuku.internal.InvalidShizukuCwdException
import com.niki914.libterm.backend.shizuku.internal.RealShizukuShellClientFactory
import com.niki914.libterm.backend.shizuku.internal.ShizukuShellClient
import com.niki914.libterm.backend.shizuku.internal.ShizukuShellClientFactory
import com.niki914.libterm.backend.shizuku.internal.ShizukuShellOutputEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ShizukuTerminalBackend internal constructor(
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val clientFactory: ShizukuShellClientFactory,
) : TerminalBackend {
    public constructor(
        context: Context,
        clock: Clock,
        scope: CoroutineScope,
    ) : this(
        clock = clock,
        scope = scope,
        clientFactory = RealShizukuShellClientFactory(context),
    )

    override val identity: TerminalIdentity = TerminalIdentity.Shizuku

    private val lifecycleMutex = Mutex()
    private val outputChunks = MutableSharedFlow<OutputChunk>(
        replay = OUTPUT_BUFFER_CAPACITY,
        extraBufferCapacity = OUTPUT_BUFFER_CAPACITY,
    )

    private var client: ShizukuShellClient? = null
    private var outputCollectionJob: Job? = null
    private var startResult: BackendStartResult? = null
    private var closed: Boolean = false

    override val output: Flow<OutputChunk> = outputChunks.asSharedFlow()

    override suspend fun start(openOptions: TerminalOpenOptions): BackendStartResult {
        return lifecycleMutex.withLock {
            startResult?.let { return@withLock it }
            if (closed) {
                return@withLock rememberStartResult(
                    BackendStartResult.Failed(
                        TerminalFailure.StartupFailed(
                            identity = identity,
                            message = "Shizuku backend is closed",
                        ),
                    ),
                )
            }

            try {
                val openedClient = clientFactory.open(openOptions.cwd.orEmpty())
                client = openedClient
                outputCollectionJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    openedClient.output.collect { event ->
                        outputChunks.emit(event.toOutputChunk())
                    }
                }
                rememberStartResult(BackendStartResult.Started)
            } catch (error: Throwable) {
                if (error is CancellationException) {
                    throw error
                }
                rememberStartResult(
                    BackendStartResult.Failed(
                        when (error) {
                            is InvalidShizukuCwdException -> TerminalFailure.InvalidOpenOptions(
                                identity = identity,
                                message = error.message,
                            )

                            else -> TerminalFailure.StartupFailed(
                                identity = identity,
                                message = error.message,
                                cause = error,
                            )
                        },
                    ),
                )
            }
        }
    }

    override suspend fun send(input: TerminalBytes): SendResult {
        val currentClient = lifecycleMutex.withLock {
            client ?: return@withLock null
        }
        return currentClient?.write(input) ?: SendResult.Failed(
            TerminalFailure.AlreadyClosed(
                identity = identity,
                message = "Shizuku backend has not been started",
            ),
        )
    }

    override suspend fun close() {
        val currentClient = lifecycleMutex.withLock {
            if (closed) {
                return
            }
            closed = true
            client
        }

        currentClient?.close()
        outputCollectionJob?.cancel()
    }

    override suspend fun awaitExit(): TerminalFailure? {
        val target = lifecycleMutex.withLock {
            val failedStart = (startResult as? BackendStartResult.Failed)?.failure
            if (failedStart != null) {
                return@withLock AwaitTarget.Failure(failedStart)
            }

            client?.let { return@withLock AwaitTarget.Client(it) }

            AwaitTarget.Failure(
                TerminalFailure.StartupFailed(
                    identity = identity,
                    message = "Shizuku backend has not been started",
                ),
            )
        }

        return when (target) {
            is AwaitTarget.Failure -> target.failure
            is AwaitTarget.Client -> target.client.awaitExit()
        }
    }

    private fun rememberStartResult(result: BackendStartResult): BackendStartResult {
        startResult = result
        return result
    }

    private fun ShizukuShellOutputEvent.toOutputChunk(): OutputChunk {
        return when (this) {
            is ShizukuShellOutputEvent.Stdout -> OutputChunk(
                stream = OutputStream.STDOUT,
                bytes = bytes,
                timestampMillis = clock.nowMillis(),
            )

            is ShizukuShellOutputEvent.Stderr -> OutputChunk(
                stream = OutputStream.STDERR,
                bytes = bytes,
                timestampMillis = clock.nowMillis(),
            )
        }
    }

    private sealed interface AwaitTarget {
        data class Client(val client: ShizukuShellClient) : AwaitTarget

        data class Failure(val failure: TerminalFailure) : AwaitTarget
    }

    private companion object {
        private const val OUTPUT_BUFFER_CAPACITY = 64
    }
}
