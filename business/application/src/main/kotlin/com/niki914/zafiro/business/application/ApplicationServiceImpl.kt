package com.niki914.zafiro.business.application

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.activity.result.ActivityResultLauncher
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

// TODO: 扫一下全仓直接依赖 Context/Activity 的位置（AppServices 登记点/MainActivity 之外的
//  散落点），确认 ApplicationService 的能力方法是否覆盖、还有没有该收编未收编的前台能力。
class ApplicationServiceImpl(
    private val application: Application,
) : ApplicationService {

    @Volatile
    private var foreground: Activity? = null

    @Volatile
    private var launcher: ActivityResultLauncher<String>? = null

    private val lock = Any()
    private var resumeGen = 0L
    private val resumeWaiters = mutableListOf<CancellableContinuation<Unit>>()
    private var permissionWaiter: CancellableContinuation<RuntimeDialogResult>? = null

    /** MainActivity.onCreate：预注册 launcher 后装进来（STARTED 前注册是框架要求）。 */
    override fun installPermissionLauncher(launcher: ActivityResultLauncher<String>) {
        this.launcher = launcher
    }

    /** App.onCreate：注册一次，之后前台跟踪全自动，无需 Activity 手动转发。 */
    fun attach() {
        application.registerActivityLifecycleCallbacks(callbacks)
    }

    fun detach() {
        application.unregisterActivityLifecycleCallbacks(callbacks)
        clearForeground(CancellationException("application service detached"))
    }

    /** launcher 回调转发（MainActivity 预注册的 launcher 回调里调）。 */
    override fun onRuntimePermissionResult(granted: Boolean) {
        val w = synchronized(lock) { permissionWaiter.also { permissionWaiter = null } }
        val result = if (granted) RuntimeDialogResult.GRANTED else RuntimeDialogResult.DENIED
        if (w?.isActive == true) w.resume(result)
    }

    override suspend fun awaitNextResume(timeoutMs: Long): Boolean {
        val gen = currentGeneration()
        return withTimeoutOrNull(timeoutMs) { awaitResumeAfter(gen) } != null
    }

    override fun getApplication(): Application = application

    override fun getActivity(): Activity? {
        val a = foreground
        return if (a != null && !a.isFinishing && !a.isDestroyed) a else null
    }

    override suspend fun awaitForeground(timeoutMs: Long): Activity? {
        getActivity()?.let { return it }
        val gen = currentGeneration()
        withTimeoutOrNull(timeoutMs) { awaitResumeAfter(gen) } ?: return getActivity()
        return getActivity()
    }

    override suspend fun requestRuntimePermission(permission: String): RuntimeDialogResult {
        val launcher = launcher ?: return RuntimeDialogResult.NOT_SHOWN
        return suspendCancellableCoroutine { cont ->
            synchronized(lock) { permissionWaiter = cont }
            cont.invokeOnCancellation {
                synchronized(lock) { if (permissionWaiter === cont) permissionWaiter = null }
            }
            // 非 resumed 状态 launch 会抛：本次框没弹成，交回 NOT_SHOWN，让链继续降级。
            runCatching { launcher.launch(permission) }
                .onFailure {
                    synchronized(lock) { if (permissionWaiter === cont) permissionWaiter = null }
                    if (cont.isActive) cont.resume(RuntimeDialogResult.NOT_SHOWN)
                }
        }
    }

    private fun currentGeneration(): Long = synchronized(lock) { resumeGen }

    private suspend fun awaitResumeAfter(gen: Long) {
        if (synchronized(lock) { resumeGen != gen }) return
        suspendCancellableCoroutine { cont ->
            val registered = synchronized(lock) {
                if (resumeGen != gen) {
                    false
                } else {
                    resumeWaiters += cont
                    true
                }
            }
            if (!registered) {
                if (cont.isActive) cont.resume(Unit)
                return@suspendCancellableCoroutine
            }
            cont.invokeOnCancellation {
                synchronized(lock) { resumeWaiters -= cont }
            }
        }
    }

    private fun onActivityResumed(activity: Activity) {
        foreground = activity
        val waiters = synchronized(lock) {
            resumeGen++
            resumeWaiters.toList().also { resumeWaiters.clear() }
        }
        waiters.forEach { if (it.isActive) it.resume(Unit) }
    }

    private fun onActivityPaused(activity: Activity) {
        if (foreground === activity) foreground = null
    }

    private fun clearForeground(cause: CancellationException) {
        foreground = null
        val resume: List<CancellableContinuation<Unit>>
        val waiter: CancellableContinuation<RuntimeDialogResult>?
        synchronized(lock) {
            resume = resumeWaiters.toList().also { resumeWaiters.clear() }
            waiter = permissionWaiter.also { permissionWaiter = null }
        }
        resume.forEach { it.cancel(cause) }
        waiter?.cancel(cause)
    }

    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) = this@ApplicationServiceImpl.onActivityResumed(activity)
        override fun onActivityPaused(activity: Activity) = this@ApplicationServiceImpl.onActivityPaused(activity)
        override fun onActivityDestroyed(activity: Activity) {
            if (foreground === activity) {
                clearForeground(CancellationException("activity destroyed"))
            }
        }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    }
}
