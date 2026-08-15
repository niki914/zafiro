package com.niki914.nexus.agentic.chat.agentic.python

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.IBinder
import android.os.RemoteException
import com.niki914.logging.Logger
import com.niki914.nexus.xposed.api.util.ContextProvider
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
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

    private const val LOG_TAG = "niki914_nexus_PyRuntime"

    // 锁死检测的 ping 窗口：与 worker 的 30s 初始化上限对齐，覆盖慢设备冷启动
    // （Python.start 首次可达数十秒）。ping 只是预检优化——真正的锁死由 exec
    // 的 timeout+2s 兜底，放宽窗口不损失正确性。
    private const val PING_TIMEOUT_MS = 30_000L
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

    /** Test hook: shorten the ping window so stuck-interpreter tests run fast. */
    @Volatile
    internal var pingTimeoutMsOverride: Long? = null

    internal fun resetForTest() {
        testService = null
        pingTimeoutMsOverride = null
        pythonUsed = false
        terminatePending = false
        activeExecCount.set(0)
        service = null
        bound = false
        appContext = null
    }

    /** True when the current worker process has executed python since warm-up. */
    @Volatile
    private var pythonUsed = false

    /**
     * P1 竞态收口：kill() 在 service 为 null（killAndReconnect 的重连窗口内）
     * 时无法立即杀进程，把终止意图记在这里；killAndReconnect 完成 bind 后
     * 检查并消费，杀掉刚重连的 worker，让在途 exec 终止。
     * 仅在 exec 在途时记录（activeExecCount > 0），避免 idle 状态的终止键
     * 污染下一次调用。
     */
    @Volatile
    private var terminatePending = false

    private val activeExecCount = AtomicInteger(0)

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
        // 等待解释器真正就绪（幂等）：Python.start 在 worker 的 HandlerThread
        // 异步执行，首次调用若未就绪会让 ping 误判为卡死并触发杀进程重连。
        val svc = testService ?: service ?: return
        if (!isHealthy(svc)) {
            // 启动窗口未完成——留给首次 exec 的重连逻辑处理，预热不杀进程
            return
        }
    }

    /**
     * Execute Python code in the worker process and return captured stdout.
     * Never returns a stuck result: a hard-stuck interpreter is killed and
     * the connection re-established before the retry.
     */
    suspend fun exec(code: String, timeoutMs: Long): String {
        pythonUsed = true
        activeExecCount.incrementAndGet()
        val startedAtMs = System.currentTimeMillis()
        Logger.i(LOG_TAG, "python exec start codeLength=${code.length} timeoutMs=$timeoutMs")
        try {
            return execInternal(code, timeoutMs).also { result ->
                Logger.i(
                    LOG_TAG,
                    "python exec done codeLength=${code.length} resultLength=${result.length} " +
                        "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Logger.w(
                LOG_TAG,
                "python exec failed codeLength=${code.length} " +
                    "errorType=${error::class.simpleName} message=${error.message} " +
                    "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
            )
            throw error
        } finally {
            activeExecCount.decrementAndGet()
        }
    }

    private suspend fun execInternal(code: String, timeoutMs: Long): String {
        ensureConnected()
        var svc = testService ?: service ?: throw PythonWorkerUnavailableException()
        if (!isHealthy(svc)) {
            killAndReconnect()
            svc = testService ?: service ?: throw PythonWorkerUnavailableException()
            // 新进程冷启动中：等待就绪再执行，避免未就绪的 exec 被兜底超时
            // 误判为卡死而再次杀进程（循环重连）
            if (!isHealthy(svc)) {
                throw PythonWorkerUnavailableException()
            }
        }
        return execOn(svc, code, timeoutMs)
    }

    /** Kill the worker process — used by the round-terminate path. */
    suspend fun kill() {
        if (!pythonUsed) return
        pythonUsed = false
        Logger.i(LOG_TAG, "python worker kill activeExecCount=${activeExecCount.get()}")
        val target = testService ?: service
        service = null
        if (target == null) {
            // 重连窗口内（service 尚未就绪）且有 exec 在途：记下终止意图，
            // 由 killAndReconnect 在 bind 完成后收口——否则在途 exec 会继续
            // 使用重连后的新 worker。idle 状态下不记录，避免污染下一次调用。
            if (activeExecCount.get() > 0) {
                terminatePending = true
            }
            return
        }
        try {
            withContext(Dispatchers.IO) { target.kill() }
        } catch (_: Throwable) {
            // process dies mid-transact — expected
        }
    }

    private fun pingTimeoutMs(): Long = pingTimeoutMsOverride ?: PING_TIMEOUT_MS

    private suspend fun isHealthy(svc: IPythonWorkerService): Boolean =
        withTimeoutOrNull(pingTimeoutMs()) {
            try {
                withContext(Dispatchers.IO) { svc.ping() } != null
            } catch (_: RemoteException) {
                false
            } catch (_: DeadObjectException) {
                false
            }
        } ?: false

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
        Logger.w(LOG_TAG, "python worker kill & reconnect")
        // 注意：不重置 pythonUsed。重连后的在途 exec 仍是本次周期内的 Python 工具，
        // 终止键必须能杀掉它（P1：健康检查失败后的重连不能关闭终止保护）。
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
            if (terminatePending) {
                // 重连期间终止键已按：杀掉刚重连的 worker，终止在途 exec。
                // 消费掉 pending，避免误伤后续调用。
                terminatePending = false
                val fresh = service
                service = null
                fresh?.let {
                    try {
                        withContext(Dispatchers.IO) { it.kill() }
                    } catch (_: Throwable) {
                    }
                }
                Logger.w(LOG_TAG, "python worker terminated during reconnect")
                throw CancellationException("Python worker terminated during reconnect")
            }
        }
    }
}
