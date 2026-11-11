package com.niki914.xposed.api.util

import android.app.Activity
import java.lang.ref.WeakReference

/**
 * r30：最近一次前台（resumed）[Activity] 的弱引用持有者。
 *
 * 用途：IAB 整页截图（[com.niki914.zafiro.chat.agentic.iab.IabManager]）在标签 WebView 已脱离
 * 窗口树（IAB 窗格收起 / 关闭）时，需要把一个**瞬时全屏透明 Dialog 宿主**挂到一个真实
 * Activity 上，让活 WebView 被重新 reparent 到真实合成器表面后再强制软件重光栅（Chromium
 * 不给脱离窗口的 WebView 产 paint 帧，r29 离屏 overlay 窗口同因失效）。
 *
 * 与 [ContextProvider]（应用级 Context，App 启动时 provide 一次）不同：Activity 是生命周期
 * 对象——由 App 侧 `Application.registerActivityLifecycleCallbacks` 在
 * [onActivityResumed] 时刷新、[onActivityDestroyed] 时清除（见 App.kt），
 * 因此这里用弱引用持有、可随时被换掉，绝不阻止 Activity 回收。
 *
 * 主进程内 [MainActivity] 是 `singleTask` + exported，同一时刻至多一个实例——
 * 弱引用丢失（刚销毁）即返回 null，调用方以 IAB_SCREENSHOT_FAILED 收敛，绝不挂起。
 */
object ActivityProvider {

    @Volatile
    private var current: WeakReference<Activity>? = null

    /** 主线程（Activity 生命周期回调）调用：刷新为当前前台 Activity。 */
    fun provide(activity: Activity) {
        current = WeakReference(activity)
    }

    /**
     * 取最近一次前台 Activity；已销毁 / 冷启动早期未 resumed 时为 null。
     * 非挂起（生命周期回调是同步的，无法 await），调用方自行 null 收敛。
     */
    fun current(): Activity? = current?.get()

    /** 测试用：清空引用。 */
    fun clear() {
        current = null
    }
}
