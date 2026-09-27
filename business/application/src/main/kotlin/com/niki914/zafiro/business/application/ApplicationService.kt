package com.niki914.zafiro.business.application

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.Manifest
import androidx.activity.result.ActivityResultLauncher

/** 系统通知权限框的结果。 */
enum class NotificationDialogResult {
    GRANTED,
    DENIED,

    /** 框没弹成（无前台 / launcher 拉不起来）：本次无结果，不是用户拒绝。 */
    NOT_SHOWN,
}

/**
 * 应用级前台能力出口：需要 Activity 才能做的事（跳设置页、弹系统权限框、等前台）
 * 全部经这里的悬挂式能力方法，业务方与权限引擎不再直连 Activity API。
 *
 * 前台 Activity 跟踪由实现侧经 Application.registerActivityLifecycleCallbacks
 * 自动维护，确定性设置/清空，不靠 WeakReference 的 GC 时机。
 *
 * 通知权限框是唯一的系统权限弹窗：overlay / accessibility / root / shizuku 都由 shell
 * 通道或跳设置页解决，而预注册 launcher 只有 Activity 能做，所以这里保留这一个具体方法，
 * 不抽象成通用的权限弹窗分派。
 */
interface ApplicationService {

    /** 应用上下文：建 NotificationManager、读资源等不需要前台的场景用它。 */
    fun getApplication(): Application // TODO 看看 ContextProvider 的代码，在那里打个注释，以后把它删掉，然后把 context provider 里面比较好的机制学习一下，跟我讨论一下。

    /**
     * 当前前台 Activity；后台或无前台时为 null。
     * 调用方每次用每次判空，不要缓存；返回前已过滤 finishing/destroyed。
     */
    fun getActivity(): Activity?

    /**
     * 等一次前台。已在前台立即返回，否则挂起至 resume 代数推进或超时。
     * 跳设置页前/后“等事件”的场景用它，而不是轮询 getActivity()。
     */
    suspend fun awaitForeground(timeoutMs: Long): Activity?

    /**
     * 经 MainActivity 预注册的通知权限 launcher 弹一次系统框并等结果。
     * launcher 必须在 Activity STARTED 前注册，因此仍由 MainActivity 预注册后装进来，
     * 实现侧只持有结果路由，不认识 launcher 的注册细节。
     */
    suspend fun requestPostNotifications(): NotificationDialogResult

    /**
     * 等下一次 resume（用户从设置页/系统框回来）。超时或取消返回 false。
     * 跳设置页“launch → 等回来 → 复查状态”语义用它，而不是各调用方自建计数器。
     */
    suspend fun awaitNextResume(timeoutMs: Long): Boolean

    /** 装配方法（仅 MainActivity 调）：预注册的 launcher 在 STARTED 前装进来。 */
    fun installNotificationLauncher(launcher: ActivityResultLauncher<String>)

    /** 装配方法（仅 MainActivity 调）：launcher 结果回调转发。 */
    fun onNotificationResult(granted: Boolean)
}
