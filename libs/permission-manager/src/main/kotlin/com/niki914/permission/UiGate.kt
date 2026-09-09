package com.niki914.permission

import android.app.Activity
import androidx.activity.result.ActivityResultLauncher
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * UI 依附点：Activity 绑定、resume 代数、通知弹窗结果路由。
 * 未 bind 时 UI 通道一律报 UNAVAILABLE（不崩、不弹窗）。
 */
class UiGate {
    @Volatile
    var activity: Activity? = null
        private set

    @Volatile
    var notificationLauncher: ActivityResultLauncher<String>? = null

    private val lock = Any()
    private var resumeGen = 0L
    private val resumeWaiters = mutableListOf<CancellableContinuation<Unit>>()
    private var notifWaiter: CancellableContinuation<Boolean>? = null

    fun bind(activity: Activity) {
        this.activity = activity
    }

    fun unbind() {
        activity = null
        val resume: List<CancellableContinuation<Unit>>
        val notif: CancellableContinuation<Boolean>?
        synchronized(lock) {
            resume = resumeWaiters.toList().also { resumeWaiters.clear() }
            notif = notifWaiter.also { notifWaiter = null }
        }
        // Activity 销毁 = 本次请求无结果：抛取消，引擎原样上抛，不记 FAILED
        val gone = CancellationException("activity unbound")
        resume.forEach { it.cancel(gone) }
        notif?.cancel(gone)
    }

    /** MainActivity.onResume 转发，每次调用推进一代并唤醒等待者。 */
    fun onActivityResumed() {
        val waiters = synchronized(lock) {
            resumeGen++
            resumeWaiters.toList().also { resumeWaiters.clear() }
        }
        waiters.forEach { if (it.isActive) it.resume(Unit) }
    }

    fun currentGeneration(): Long = synchronized(lock) { resumeGen }

    /**
     * 等待下一次 resume。若 [gen] 已过期（等待期间发生过 resume）立即返回。
     */
    suspend fun awaitResumeAfter(gen: Long) {
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

    /** 通知弹窗结果等待（单槽；并发弹窗由 handler 侧 Mutex 串行）。 */
    suspend fun awaitNotificationResult(): Boolean =
        suspendCancellableCoroutine { cont ->
            synchronized(lock) { notifWaiter = cont }
            cont.invokeOnCancellation {
                synchronized(lock) { if (notifWaiter === cont) notifWaiter = null }
            }
        }

    /** launcher 回调转发（MainActivity 预注册的 launcher 回调里调）。 */
    fun onNotificationResult(granted: Boolean) {
        val w = synchronized(lock) { notifWaiter.also { notifWaiter = null } }
        if (w?.isActive == true) w.resume(granted)
    }
}
