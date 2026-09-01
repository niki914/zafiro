package com.niki914.libterm.backend.shizuku.internal

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import com.niki914.libterm.SendResult
import com.niki914.libterm.TerminalBytes
import com.niki914.libterm.TerminalFailure
import com.niki914.libterm.TerminalIdentity
import com.niki914.libterm.backend.shizuku.ILibTermShizukuShellCallback
import com.niki914.libterm.backend.shizuku.ILibTermShizukuShellService
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class ShizukuUserServiceClient(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ShizukuShellClient {
    private val outputEvents = MutableSharedFlow<ShizukuShellOutputEvent>(
        replay = OUTPUT_BUFFER_CAPACITY,
        extraBufferCapacity = OUTPUT_BUFFER_CAPACITY,
    )
    private val exitSignal = CompletableDeferred<TerminalFailure?>()
    private val closed = AtomicBoolean(false)
    private val connectActive = AtomicBoolean(false)
    private val stateLock = Any()
    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(
            context.packageName,
            LibTermShizukuShellUserService::class.java.name,
        ),
    ).apply {
        daemon(false)
        debuggable(false)
        processNameSuffix(ShizukuShellConstants.USER_SERVICE_PROCESS_SUFFIX)
    }

    private var service: ILibTermShizukuShellService? = null
    private var sessionId: Long? = null
    private var bound: Boolean = false

    @Volatile
    private var pendingCwd: String = ""
    private val pendingOutputEvents = mutableListOf<PendingOutputEvent>()

    override val output: Flow<ShizukuShellOutputEvent> = outputEvents.asSharedFlow()

    private val callback = object : ILibTermShizukuShellCallback.Stub() {
        override fun onOutput(sessionId: Long, stream: Int, data: ByteArray) {
            val bytes = TerminalBytes.of(data)
            val event = when (stream) {
                ShizukuShellConstants.STREAM_STDOUT -> ShizukuShellOutputEvent.Stdout(bytes)
                ShizukuShellConstants.STREAM_STDERR -> ShizukuShellOutputEvent.Stderr(bytes)

                else -> return
            }
            val shouldEmit = synchronized(stateLock) {
                when (this@ShizukuUserServiceClient.sessionId) {
                    sessionId -> true
                    null -> {
                        pendingOutputEvents += PendingOutputEvent(sessionId, event)
                        false
                    }

                    else -> false
                }
            }
            if (shouldEmit) {
                emitOutputEvent(event)
            }
        }

        override fun onClosed(sessionId: Long, exitCode: Int) {
            if (isCurrentSession(sessionId)) {
                exitSignal.complete(null)
            }
        }

        override fun onError(sessionId: Long, message: String?) {
            if (isCurrentSession(sessionId)) {
                exitSignal.complete(runtimeTerminated(message ?: "Shizuku shell reported an error"))
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (!connectActive.get()) {
                unbindIfBound()
                return
            }
            val connectedService = ILibTermShizukuShellService.Stub.asInterface(binder)
            val currentCwd = pendingCwd
            try {
                val openedSessionId = connectedService.openSession(currentCwd, callback)
                val pendingEvents = mutableListOf<ShizukuShellOutputEvent>()
                synchronized(stateLock) {
                    service = connectedService
                    sessionId = openedSessionId
                    val iterator = pendingOutputEvents.iterator()
                    while (iterator.hasNext()) {
                        val pendingEvent = iterator.next()
                        if (pendingEvent.sessionId == openedSessionId) {
                            pendingEvents += pendingEvent.event
                        }
                        iterator.remove()
                    }
                }
                pendingEvents.forEach { event ->
                    emitOutputEvent(event)
                }
                connectActive.set(false)
                resumeConnectSuccess()
            } catch (error: Throwable) {
                val failure = openSessionFailed(currentCwd, error)
                exitSignal.complete(failure)
                connectActive.set(false)
                unbindIfBound()
                resumeConnectFailure(failure.asConnectException())
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            val failure = runtimeTerminated("Shizuku user service disconnected")
            exitSignal.complete(failure)
            connectActive.set(false)
            resumeConnectFailure(IllegalStateException(failure.message))
        }

        override fun onBindingDied(name: ComponentName?) {
            val failure = runtimeTerminated("Shizuku user service binding died")
            exitSignal.complete(failure)
            connectActive.set(false)
            resumeConnectFailure(IllegalStateException(failure.message))
        }
    }

    private var connectResult: CancellableContinuation<Unit>? = null

    suspend fun connect(cwd: String) {
        pendingCwd = cwd
        try {
            withTimeout(CONNECT_TIMEOUT_MILLIS) {
                bindUserService()
            }
        } catch (error: TimeoutCancellationException) {
            throw IllegalStateException("Timed out binding Shizuku user service", error)
        }
    }

    private suspend fun bindUserService() {
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                connectResult = continuation
                try {
                    connectActive.set(true)
                    Shizuku.bindUserService(userServiceArgs, connection)
                    bound = true
                } catch (error: Throwable) {
                    connectActive.set(false)
                    resumeConnectFailure(error)
                }
                continuation.invokeOnCancellation {
                    connectResult = null
                    connectActive.set(false)
                    unbindIfBound()
                }
            }
        }
    }

    override suspend fun write(input: TerminalBytes): SendResult {
        val target = synchronized(stateLock) {
            val currentService = service
            val currentSessionId = sessionId
            if (currentService == null || currentSessionId == null) {
                return@synchronized null
            }
            currentService to currentSessionId
        } ?: return SendResult.Failed(
            TerminalFailure.AlreadyClosed(
                identity = TerminalIdentity.Shizuku,
                message = "Shizuku backend has not been started",
            ),
        )

        return withContext(ioDispatcher) {
            try {
                target.first.write(target.second, input.toByteArray())
                SendResult.Sent
            } catch (error: RemoteException) {
                val failure = runtimeTerminated("Failed to write Shizuku shell stdin", error)
                exitSignal.complete(failure)
                SendResult.Failed(failure)
            }
        }
    }

    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        connectActive.set(false)

        val target = synchronized(stateLock) {
            val currentService = service
            val currentSessionId = sessionId
            service = null
            sessionId = null
            if (currentService == null || currentSessionId == null) {
                null
            } else {
                currentService to currentSessionId
            }
        }

        withContext(ioDispatcher) {
            runCatching {
                target?.first?.close(target.second)
            }
        }
        withContext(Dispatchers.Main.immediate) {
            unbindIfBound()
        }
        exitSignal.complete(null)
    }

    override suspend fun awaitExit(): TerminalFailure? {
        return exitSignal.await()
    }

    private fun isCurrentSession(candidate: Long): Boolean {
        return synchronized(stateLock) {
            sessionId == candidate
        }
    }

    private fun emitOutputEvent(event: ShizukuShellOutputEvent) {
        if (!outputEvents.tryEmit(event)) {
            exitSignal.complete(runtimeTerminated("Shizuku shell output buffer overflow"))
        }
    }

    private fun unbindIfBound() {
        if (!bound) {
            return
        }
        runCatching {
            Shizuku.unbindUserService(userServiceArgs, connection, true)
        }
        bound = false
    }

    private fun resumeConnectSuccess() {
        val continuation = connectResult ?: return
        connectResult = null
        if (continuation.isActive) {
            continuation.resume(Unit)
        }
    }

    private fun resumeConnectFailure(error: Throwable) {
        val continuation = connectResult ?: return
        connectResult = null
        if (continuation.isActive) {
            continuation.resumeWithException(error)
        }
    }

    private fun startupFailed(message: String, cause: Throwable): TerminalFailure.StartupFailed {
        return TerminalFailure.StartupFailed(
            identity = TerminalIdentity.Shizuku,
            message = message,
            cause = cause,
        )
    }

    private fun runtimeTerminated(
        message: String,
        cause: Throwable? = null,
    ): TerminalFailure.RuntimeTerminated {
        return TerminalFailure.RuntimeTerminated(
            identity = TerminalIdentity.Shizuku,
            message = message,
            cause = cause,
        )
    }

    private fun openSessionFailed(cwd: String, cause: Throwable): TerminalFailure {
        if (cwd.isNotEmpty() && cause.isUserServiceStartFailure()) {
            return TerminalFailure.InvalidOpenOptions(
                identity = TerminalIdentity.Shizuku,
                message = "Invalid cwd: $cwd",
            )
        }
        return startupFailed("Failed to open Shizuku shell session", cause)
    }

    private fun Throwable.isUserServiceStartFailure(): Boolean {
        return message == USER_SERVICE_START_FAILURE_MESSAGE
    }

    private fun TerminalFailure.asConnectException(): Throwable {
        return when (this) {
            is TerminalFailure.InvalidOpenOptions -> InvalidShizukuCwdException(message)
            else -> IllegalStateException(message)
        }
    }

    private companion object {
        private const val OUTPUT_BUFFER_CAPACITY = 64
        private const val CONNECT_TIMEOUT_MILLIS = 15_000L
        private const val USER_SERVICE_START_FAILURE_MESSAGE = "Failed to start Shizuku shell"
    }

    private data class PendingOutputEvent(
        val sessionId: Long,
        val event: ShizukuShellOutputEvent,
    )
}

internal class InvalidShizukuCwdException(
    message: String?,
) : IllegalStateException(message)
