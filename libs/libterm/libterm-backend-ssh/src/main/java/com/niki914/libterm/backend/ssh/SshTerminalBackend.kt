package com.niki914.libterm.backend.ssh

import com.niki914.libterm.BackendStartResult
import com.niki914.libterm.Clock
import com.niki914.libterm.OutputChunk
import com.niki914.libterm.OutputStream
import com.niki914.libterm.SendResult
import com.niki914.libterm.SshOpenOptions
import com.niki914.libterm.TerminalBackend
import com.niki914.libterm.TerminalBytes
import com.niki914.libterm.TerminalFailure
import com.niki914.libterm.TerminalIdentity
import com.niki914.libterm.TerminalOpenOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SshTerminalBackend(
    private val options: SshOpenOptions,
    private val clock: Clock,
    private val scope: CoroutineScope,
) : TerminalBackend {
    internal constructor(
        options: SshOpenOptions,
        clock: Clock,
        scope: CoroutineScope,
        adapterFactory: SshShellAdapterFactory,
    ) : this(
        options = options,
        clock = clock,
        scope = scope,
    ) {
        this.adapterFactory = adapterFactory
    }

    override val identity: TerminalIdentity = TerminalIdentity.Ssh

    private val lifecycleMutex = Mutex()
    private var adapterFactory: SshShellAdapterFactory = JschSshShellAdapterFactory()
    private val outputChunks = MutableSharedFlow<OutputChunk>(
        replay = OUTPUT_BUFFER_CAPACITY,
        extraBufferCapacity = OUTPUT_BUFFER_CAPACITY,
    )

    private var adapter: SshShellAdapter? = null
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
                        TerminalFailure.SshChannelFailed(
                            message = "SSH backend is closed",
                        ),
                    ),
                )
            }

            try {
                val openedAdapter = adapterFactory.open(options)
                adapter = openedAdapter
                outputCollectionJob = scope.launch {
                    openedAdapter.output.collect { bytes ->
                        outputChunks.emit(
                            OutputChunk(
                                stream = OutputStream.STDOUT,
                                bytes = bytes,
                                timestampMillis = clock.nowMillis(),
                            ),
                        )
                    }
                }
                rememberStartResult(BackendStartResult.Started)
            } catch (error: Throwable) {
                if (error is CancellationException) {
                    throw error
                }
                rememberStartResult(
                    BackendStartResult.Failed(
                        JschFailureMapper.mapStartFailure(options, error),
                    ),
                )
            }
        }
    }

    override suspend fun send(input: TerminalBytes): SendResult {
        val currentAdapter = lifecycleMutex.withLock {
            if (closed) {
                return@withLock null
            }
            adapter
        }

        return try {
            currentAdapter?.write(input) ?: return SendResult.Failed(
                TerminalFailure.AlreadyClosed(
                    identity = TerminalIdentity.Ssh,
                    message = "SSH backend has not been started",
                ),
            )
            SendResult.Sent
        } catch (error: Throwable) {
            if (error is CancellationException) {
                throw error
            }
            SendResult.Failed(JschFailureMapper.mapRuntimeFailure(error))
        }
    }

    override suspend fun close() {
        val currentAdapter = lifecycleMutex.withLock {
            if (closed) {
                return
            }
            closed = true
            adapter
        }

        currentAdapter?.close()
        outputCollectionJob?.cancel()
    }

    override suspend fun awaitExit(): TerminalFailure? {
        val target = lifecycleMutex.withLock {
            val failedStart = (startResult as? BackendStartResult.Failed)?.failure
            if (failedStart != null) {
                return@withLock AwaitTarget.Failure(failedStart)
            }

            adapter?.let { return@withLock AwaitTarget.Adapter(it) }

            AwaitTarget.Failure(
                TerminalFailure.SshChannelFailed(
                    message = "SSH backend has not been started",
                ),
            )
        }

        return when (target) {
            is AwaitTarget.Failure -> target.failure
            is AwaitTarget.Adapter -> {
                try {
                    target.adapter.awaitExit()?.let(JschFailureMapper::mapRuntimeFailure)
                } catch (error: Throwable) {
                    if (error is CancellationException) {
                        throw error
                    }
                    JschFailureMapper.mapRuntimeFailure(error)
                }
            }
        }
    }

    private fun rememberStartResult(result: BackendStartResult): BackendStartResult {
        startResult = result
        return result
    }

    private sealed interface AwaitTarget {
        data class Adapter(val adapter: SshShellAdapter) : AwaitTarget

        data class Failure(val failure: TerminalFailure) : AwaitTarget
    }

    private companion object {
        private const val OUTPUT_BUFFER_CAPACITY = 64
    }
}
