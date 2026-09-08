package com.niki914.permission

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.niki914.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * SHIZUKU 通道（独立实现，与 libterm 共存；战略迁移完成前不合流）。
 *
 * binder 是 Shizuku server 在应用启动后异步推送的（sendBinder），调 getBinder
 * 要不来（且 provider 的 call 要求 extras 非空，传 null 直接返回空）。
 * 因此 status() 只如实上报当前快照，request() 会等 binder 一段时间再判 UNAVAILABLE。
 *
 * 命令执行走反射调 Shizuku.newProcess（private，但依赖版本锁死 13.1.5，
 * 内部仅转发 IShizukuService.newProcess → ShizukuRemoteProcess）。
 * 升级 shizuku-api 前先确认 newProcess 可见性。
 *
 * 支持 ROOT / SHIZUKU（能力自查）/ OVERLAY / ACCESSIBILITY，其余返回 UNAVAILABLE。
 */
class ShizukuHandler(
    private val context: Context,
    private val packageName: String,
    private val accessibilityService: ComponentName? = null,
) : ChannelHandler {

    override val channel = Channel.SHIZUKU
    override val minSdk = MinSdk(23) // Shizuku 自身要求 23+

    override fun status(permission: Permission): PermissionState {
        if (!permission.isSupported) return PermissionState.UNAVAILABLE
        return when (permission) {
            Permission.OVERLAY -> TargetStatus.overlay(context)
            Permission.ACCESSIBILITY -> TargetStatus.accessibility(context, accessibilityService)
            Permission.ROOT, Permission.SHIZUKU -> {
                if (!pingBinder()) {
                    Logger.d(TAG, "status($permission): binder not alive -> UNAVAILABLE")
                    return PermissionState.UNAVAILABLE
                }
                val version = runCatching { Shizuku.getVersion() }.getOrNull()
                val check = runCatching { Shizuku.checkSelfPermission() }.getOrElse { e ->
                    Logger.d(
                        TAG,
                        "status($permission): checkSelfPermission threw " +
                            "${e.javaClass.simpleName} -> UNAVAILABLE",
                    )
                    return PermissionState.UNAVAILABLE
                }
                Logger.d(TAG, "status($permission): version=$version check=$check")
                when (check) {
                    PackageManager.PERMISSION_GRANTED -> PermissionState.GRANTED
                    PackageManager.PERMISSION_DENIED -> PermissionState.DENIED_BY_USER
                    else -> PermissionState.UNKNOWN
                }
            }
            else -> PermissionState.UNAVAILABLE
        }
    }

    override suspend fun request(permission: Permission): PermissionState {
        if (!permission.isSupported) return PermissionState.UNAVAILABLE
        if (!pingBinder()) {
            Logger.d(TAG, "request($permission): binder absent, waiting up to ${BINDER_TIMEOUT_MILLIS}ms")
            if (!awaitBinder(BINDER_TIMEOUT_MILLIS)) {
                Logger.d(TAG, "request($permission): binder still absent -> UNAVAILABLE")
                return PermissionState.UNAVAILABLE
            }
        }

        val self = runCatching { Shizuku.checkSelfPermission() }.getOrElse { e ->
            if (e is CancellationException) throw e
            Logger.d(
                TAG,
                "request($permission): checkSelfPermission threw " +
                    "${e.javaClass.simpleName} -> UNAVAILABLE",
            )
            return PermissionState.UNAVAILABLE
        }
        if (self != PackageManager.PERMISSION_GRANTED) {
            Logger.d(TAG, "request($permission): self=$self, showing Shizuku dialog")
            val authorized = try {
                requestAuthorization()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Logger.d(TAG, "request($permission): auth threw ${e.javaClass.simpleName} -> UNAVAILABLE")
                return PermissionState.UNAVAILABLE
            }
            Logger.d(TAG, "request($permission): authorized=$authorized")
            if (!authorized) return PermissionState.DENIED_BY_USER
        }

        return when (permission) {
            Permission.ROOT, Permission.SHIZUKU -> PermissionState.GRANTED
            Permission.OVERLAY -> exec("appops set $packageName SYSTEM_ALERT_WINDOW allow")
            Permission.ACCESSIBILITY -> grantAccessibility()
            else -> PermissionState.UNAVAILABLE
        }
    }

    /** 等 binder 异步到达。sticky 监听已到达时立即回放，顺带处理注册竞态。 */
    private suspend fun awaitBinder(timeoutMillis: Long): Boolean {
        if (pingBinder()) return true
        return try {
            withTimeout(timeoutMillis) {
                suspendCancellableCoroutine { cont ->
                    val listener = object : Shizuku.OnBinderReceivedListener {
                        override fun onBinderReceived() {
                            Shizuku.removeBinderReceivedListener(this)
                            if (cont.isActive) cont.resume(true)
                        }
                    }
                    Shizuku.addBinderReceivedListenerSticky(listener)
                    cont.invokeOnCancellation {
                        Shizuku.removeBinderReceivedListener(listener)
                    }
                    if (pingBinder()) {
                        Shizuku.removeBinderReceivedListener(listener)
                        if (cont.isActive) cont.resume(true)
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            false
        }
    }

    private fun pingBinder(): Boolean =
        runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /** 弹 Shizuku 授权框并等结果。超时后复查一次，避免回调迟到误判拒绝。 */
    private suspend fun requestAuthorization(): Boolean {
        val requestCode = requestCodeGen.getAndIncrement()
        return try {
            withTimeout(AUTH_TIMEOUT_MILLIS) { awaitPermission(requestCode) }
        } catch (e: TimeoutCancellationException) {
            val recheck = runCatching { Shizuku.checkSelfPermission() }.getOrNull()
            Logger.d(TAG, "shizuku permission timeout(#$requestCode), recheck=$recheck")
            recheck == PackageManager.PERMISSION_GRANTED
        }
    }

    private suspend fun awaitPermission(requestCode: Int): Boolean =
        suspendCancellableCoroutine { cont ->
            val listener = object : Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(rc: Int, grantResult: Int) {
                    if (rc != requestCode) return
                    Shizuku.removeRequestPermissionResultListener(this)
                    if (cont.isActive) cont.resume(grantResult == PackageManager.PERMISSION_GRANTED)
                }
            }
            Shizuku.addRequestPermissionResultListener(listener)
            cont.invokeOnCancellation {
                Shizuku.removeRequestPermissionResultListener(listener)
            }
            try {
                Shizuku.requestPermission(requestCode)
            } catch (e: Throwable) {
                Shizuku.removeRequestPermissionResultListener(listener)
                if (cont.isActive) {
                    if (e is CancellationException) cont.cancel(e)
                    else cont.resumeWithException(e)
                }
            }
        }

    private suspend fun grantAccessibility(): PermissionState {
        val service = requireNotNull(accessibilityService) {
            "accessibilityService is required for Permission.ACCESSIBILITY"
        }
        val get = run("settings get secure enabled_accessibility_services")
            ?: return PermissionState.FAILED
        val merged = get.stdout.joinToString("").trim()
            .takeUnless { it.isBlank() || it == "null" }
            ?.split(":")
            .orEmpty()
            .filter { it.isNotBlank() }
            .plus(service.flattenToShortString())
            .distinct()
            .joinToString(":")
        val put1 = run("settings put secure enabled_accessibility_services $merged")
            ?: return PermissionState.FAILED
        if (!put1.isSuccess) return PermissionState.FAILED
        val put2 = run("settings put secure accessibility_enabled 1")
            ?: return PermissionState.FAILED
        return if (put2.isSuccess) PermissionState.GRANTED else PermissionState.FAILED
    }

    private suspend fun exec(command: String): PermissionState {
        val outcome = run(command) ?: return PermissionState.FAILED
        return if (outcome.isSuccess) PermissionState.GRANTED else PermissionState.FAILED
    }

    /** null = 进程创建/执行失败。stdout/stderr 并发消费，防 stderr 撑满死锁。 */
    private suspend fun run(command: String): ShellOutcome? = withContext(Dispatchers.IO) {
        try {
            val process = newProcess(arrayOf("sh", "-c", command), null, null)
            val stdoutDeferred = async { process.inputStream.bufferedReader().readLines() }
            val stderrDeferred = async { process.errorStream.bufferedReader().readLines() }
            val exitCode = process.waitFor()
            val stdout = stdoutDeferred.await()
            val stderr = stderrDeferred.await()
            if (stderr.isNotEmpty()) {
                Logger.d(TAG, "exec stderr [$command]: ${stderr.joinToString(" | ")}")
            }
            Logger.d(TAG, "exec [$command] exit=$exitCode stdoutLines=${stdout.size}")
            ShellOutcome(exitCode = exitCode, stdout = stdout)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Logger.d(TAG, "shizuku exec failed [$command]: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    // ponytail: Shizuku.newProcess private，锁死 shizuku-api 13.1.5 前提下反射安全；
    // 升级依赖时先检查该方法是否转 public，转了就删反射
    private fun newProcess(cmd: Array<String>, env: Array<String>?, dir: String?): Process {
        val method: Method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        )
        method.isAccessible = true
        return method.invoke(null, cmd, env, dir) as Process
    }

    private val Permission.isSupported: Boolean
        get() = this == Permission.ROOT ||
            this == Permission.SHIZUKU ||
            this == Permission.OVERLAY ||
            (this == Permission.ACCESSIBILITY && accessibilityService != null)

    companion object {
        const val TAG = "ShizukuHandler"
        const val AUTH_TIMEOUT_MILLIS = 30_000L
        const val BINDER_TIMEOUT_MILLIS = 8_000L
        val requestCodeGen = AtomicInteger(1000)

        /** 诊断快照：主进程打日志用，无需直接依赖 shizuku-api */
        fun binderSnapshot(): String {
            val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
            val version = runCatching { Shizuku.getVersion() }.getOrNull()
            return "alive=$alive version=$version"
        }
    }
}
