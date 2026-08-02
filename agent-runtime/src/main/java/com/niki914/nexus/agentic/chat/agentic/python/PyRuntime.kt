package com.niki914.nexus.agentic.chat.agentic.python

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.IBinder
import android.os.RemoteException
import com.niki914.nexus.xposed.api.util.ContextProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

class PythonWorkerUnavailableException :
    IllegalStateException("Python worker is not connected")

/**
 * Client for the Python worker in the dedicated `:python` process.
 *
 * Executes code through [IPythonWorkerService] with a two-layer timeout:
 * 1. [exec] first pings the interpreter (2s) to detect a hard-stuck
 *    interpreter before paying the full exec timeout. On failure the worker
 *    process is killed and reconnected once, then the call is retried.
 * 2. The exec call itself is wrapped in `timeoutMs + 2s`. A normal timeout
 *    returns promptly from `runtime.exec_code` (it joins its own worker
 *    thread), so exceeding the wrapper means the interpreter is stuck in
 *    native code — the worker process is killed and the
 *    [TimeoutCancellationException] rethrown so callers report TIMEOUT.
 *
 * [warmUp] should be called during [android.app.Application.onCreate] to
 * bring up the worker process and start the interpreter early. [kill] is
 * wired to the round-terminate path so stopping a turn hard-stops any
 * in-flight Python tool.
 *
 * The Binder call is blocking, so every interaction is dispatched through
 * [Dispatchers.IO] — this is what makes [withTimeout] able to fire: it
 * cancels at the `withContext` boundary while the underlying Binder thread
 * stays blocked until the worker process dies.
 */
object PyRuntime {

    private const val PING_TIMEOUT_MS = 2_000L
    private const val EXEC_GRACE_MS = 2_000L
    private const val CONNECT_TIMEOUT_MS = 10_000L
    private const val PROCESS_DIE_SETTLE_MS = 200L

    private val connectionMutex = Mutex()

    @Volatile
    private var service: IPythonWorkerService? = null

    @Volatile
    private var bound = false

    @Volatile
    private var appContext: Context? = null

    /** 每次 bind 重建：完成后 [connection] 回调读最新实例。 */
    @Volatile
    private var pendingBinder = CompletableDeferred<IBinder?>()

    /** Test hook: when set, all calls bypass the Binder layer. */
    @Volatile
    internal var testService: IPythonWorkerService? = null

    internal fun resetForTest() {
        testService = null
        pythonUsed = false
        service = null
        bound = false
        appContext = null
    }

    /** True when the current worker process has executed python since warm-up. */
    @Volatile
    private var pythonUsed = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            binder?.linkToDeath(deathRecipient, 0)
            pendingBinder.complete(binder)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    private val deathRecipient = IBinder.DeathRecipient {
        service = null
    }

    suspend fun warmUp() {
        ensureConnected()
    }

    /**
     * Execute Python code in the worker process and return captured stdout.
     * Never returns a stuck result: a hard-stuck interpreter is killed and
     * the connection re-established before the retry.
     */
    suspend fun exec(code: String, timeoutMs: Long): String {
        pythonUsed = true
        ensureConnected()
        var svc = testService ?: service ?: throw PythonWorkerUnavailableException()
        val healthy = withTimeoutOrNull(PING_TIMEOUT_MS) {
            try {
                withContext(Dispatchers.IO) { svc.ping() } != null
            } catch (_: RemoteException) {
                false
            } catch (_: DeadObjectException) {
                false
            }
        } ?: false
        if (!healthy) {
            killAndReconnect()
            svc = testService ?: service ?: throw PythonWorkerUnavailableException()
        }
        return execOn(svc, code, timeoutMs)
    }

    /** Kill the worker process — used by the round-terminate path. */
    suspend fun kill() {
        if (!pythonUsed) return
        pythonUsed = false
        val target = testService ?: service ?: return
        service = null
        try {
            withContext(Dispatchers.IO) { target.kill() }
        } catch (_: Throwable) {
            // process dies mid-transact — expected
        }
    }

    private suspend fun execOn(svc: IPythonWorkerService, code: String, timeoutMs: Long): String {
        try {
            return withTimeout(timeoutMs + EXEC_GRACE_MS) {
                withContext(Dispatchers.IO) { svc.exec(code, timeoutMs) }
            } ?: ""
        } catch (e: TimeoutCancellationException) {
            killAndReconnect()
            throw e
        } catch (e: RemoteException) {
            service = null
            throw PythonWorkerUnavailableException()
        }
    }

    private suspend fun ensureConnected() {
        if (testService != null || service != null) return
        connectionMutex.withLock {
            if (testService != null || service != null) return
            val ctx = ContextProvider.await().applicationContext
            appContext = ctx
            bindLocked(ctx)
        }
    }

    private suspend fun bindLocked(ctx: Context) {
        if (bound) {
            try {
                ctx.unbindService(connection)
            } catch (_: Exception) {
            }
            bound = false
        }
        bound = try {
            ctx.bindService(
                Intent(ctx, PythonWorkerService::class.java),
                connection,
                Context.BIND_AUTO_CREATE
            )
        } catch (_: Throwable) {
            false
        }
        if (!bound) {
            service = null
            return
        }
        pendingBinder = CompletableDeferred()
        val binder = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { pendingBinder.await() }
        if (binder == null) {
            bound = false
            service = null
            try {
                ctx.unbindService(connection)
            } catch (_: Exception) {
            }
            return
        }
        service = IPythonWorkerService.Stub.asInterface(binder)
    }

    private suspend fun killAndReconnect() {
        pythonUsed = false
        val target = testService ?: service
        service = null
        if (target != null) {
            try {
                withContext(Dispatchers.IO) { target.kill() }
            } catch (_: Throwable) {
            }
        }
        if (testService != null) return
        connectionMutex.withLock {
            delay(PROCESS_DIE_SETTLE_MS)
            val ctx = appContext ?: return@withLock
            if (bound) {
                try {
                    ctx.unbindService(connection)
                } catch (_: Exception) {
                }
                bound = false
            }
            bindLocked(ctx)
        }
    }
}
