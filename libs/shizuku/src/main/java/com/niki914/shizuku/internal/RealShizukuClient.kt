package com.niki914.shizuku.internal

import android.content.Context
import android.content.pm.PackageManager
import com.niki914.logging.Logger
import com.niki914.shizuku.ShizukuClient
import com.niki914.shizuku.ShizukuExecResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider
import java.lang.reflect.Method
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.milliseconds

internal class RealShizukuClient(
    private val context: Context,
) : ShizukuClient {

    private val requestCodeGenerator = AtomicInteger(1000)

    @Suppress("DEPRECATION")
    override val isInstalled: Boolean
        get() = runCatchingCancellable {
            context.packageManager.getPackageInfo(ShizukuProvider.MANAGER_APPLICATION_ID, 0)
        }.isSuccess

    override val isBinderAlive: Boolean
        get() = runCatchingCancellable { Shizuku.pingBinder() }.getOrDefault(false)

    override val isAuthorized: Boolean
        get() = isBinderAlive && runCatchingCancellable {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

    override suspend fun awaitBinder(timeoutMillis: Long): Boolean {
        if (isBinderAlive) return true
        return try {
            withTimeout(timeoutMillis.milliseconds) {
                suspendCancellableCoroutine { continuation ->
                    val listener = object : Shizuku.OnBinderReceivedListener {
                        override fun onBinderReceived() {
                            Shizuku.removeBinderReceivedListener(this)
                            if (continuation.isActive) continuation.resume(true)
                        }
                    }
                    Shizuku.addBinderReceivedListenerSticky(listener)
                    continuation.invokeOnCancellation {
                        Shizuku.removeBinderReceivedListener(listener)
                    }
                    if (isBinderAlive) {
                        Shizuku.removeBinderReceivedListener(listener)
                        if (continuation.isActive) continuation.resume(true)
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            false
        }
    }

    override suspend fun requestPermission(timeoutMillis: Long): Boolean {
        if (isAuthorized) return true
        if (!isBinderAlive) {
            if (!isInstalled) return false
            if (!awaitBinder(minOf(timeoutMillis, ShizukuClient.DEFAULT_BINDER_TIMEOUT_MS))) return false
        }
        val requestCode = requestCodeGenerator.getAndIncrement()
        return try {
            withTimeout(timeoutMillis.milliseconds) {
                suspendCancellableCoroutine { continuation ->
                    val listener = object : Shizuku.OnRequestPermissionResultListener {
                        override fun onRequestPermissionResult(rc: Int, grantResult: Int) {
                            if (rc != requestCode) return
                            Shizuku.removeRequestPermissionResultListener(this)
                            if (continuation.isActive) {
                                continuation.resume(grantResult == PackageManager.PERMISSION_GRANTED)
                            }
                        }
                    }
                    Shizuku.addRequestPermissionResultListener(listener)
                    continuation.invokeOnCancellation {
                        Shizuku.removeRequestPermissionResultListener(listener)
                    }
                    try {
                        Shizuku.requestPermission(requestCode)
                    } catch (e: Throwable) {
                        Shizuku.removeRequestPermissionResultListener(listener)
                        if (continuation.isActive) {
                            if (e is CancellationException) continuation.cancel(e)
                            else continuation.resumeWithException(e)
                        }
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            isAuthorized
        }
    }

    override suspend fun exec(command: String, timeoutMillis: Long): ShizukuExecResult =
        withContext(Dispatchers.IO) {
            var process: Process? = null
            try {
                val proc = createProcess(arrayOf("sh", "-c", command))
                process = proc
                val stdoutDeferred = async {
                    runCatching { proc.inputStream.bufferedReader().readLines() }.getOrDefault(emptyList())
                }
                val stderrDeferred = async {
                    runCatching { proc.errorStream.bufferedReader().readLines() }.getOrDefault(emptyList())
                }

                val finished = proc.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
                if (!finished) {
                    stdoutDeferred.cancel()
                    stderrDeferred.cancel()
                    runCatching { proc.inputStream.close() }
                    runCatching { proc.errorStream.close() }
                    proc.destroy()
                    return@withContext ShizukuExecResult(
                        exitCode = -1,
                        stdout = emptyList(),
                        stderr = listOf("Timed out after ${timeoutMillis}ms"),
                    )
                }

                val stdout = stdoutDeferred.await()
                val stderr = stderrDeferred.await()
                val exitCode = proc.exitValue()
                ShizukuExecResult(exitCode = exitCode, stdout = stdout, stderr = stderr)
            } catch (e: CancellationException) {
                process?.destroy()
                throw e
            } catch (e: Throwable) {
                Logger.e(TAG, "exec failed [$command]", e)
                process?.destroy()
                ShizukuExecResult(
                    exitCode = -1,
                    stdout = emptyList(),
                    stderr = listOf("${e.javaClass.simpleName}: ${e.message}"),
                )
            }
        }

    private fun createProcess(cmd: Array<String>): Process {
        val method = newProcessMethod ?: synchronized(methodLock) { // githooks:ignore monitor-lock 反射方法单例初始化
            newProcessMethod ?: Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            ).apply {
                isAccessible = true
                newProcessMethod = this
            }
        }
        return method.invoke(null, cmd, null, null) as Process
    }

    companion object {
        private const val TAG = "niki914_zafiro_RealShizukuClient"
        private val methodLock = Any()
        @Volatile
        private var newProcessMethod: Method? = null
    }
}
