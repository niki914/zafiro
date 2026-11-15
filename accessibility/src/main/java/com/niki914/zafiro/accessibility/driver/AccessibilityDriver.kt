package com.niki914.zafiro.accessibility.driver

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.niki914.logging.Logger
import com.niki914.zafiro.api.util.runCatchingCancellable
import com.niki914.zafiro.api.util.tryOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * 无状态、多屏友好的无障碍底层驱动接口。
 *
 * 核心原则：
 * 1. 无状态（Stateless）：不持有业务状态、会话状态或版本号；
 * 2. 显式 displayId：所有与屏幕相关的调用均显式接收 displayId，原生支持多屏并发；
 * 3. 平台原语封装：对接 Android AccessibilityService 的窗口、手势与截图 API。
 */
interface AccessibilityDriver {
    val isConnected: Boolean

    /**
     * 获取指定 displayId 上当前激活/顶层的根节点。
     */
    fun getActiveWindowRoot(displayId: Int): AccessibilityNodeInfo?

    /**
     * 在指定 displayId 上分发触摸手势。
     */
    suspend fun dispatchGesture(
        displayId: Int,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        duration: Long,
    ): Boolean

    /**
     * 抓取指定 displayId 的整屏硬件位图（API 30+ 官方截屏，无需 root）。
     */
    suspend fun takeScreenshot(displayId: Int): Bitmap?

    /**
     * 在特定无障碍节点上执行语义动作（点击、长按、设置文本、滚动等）。
     */
    fun performNodeAction(
        node: AccessibilityNodeInfo,
        action: Int,
        text: String? = null,
    ): Boolean

    /**
     * 获取指定 displayId 的真实屏幕物理分辨率（宽 x 高）。
     */
    fun getDisplayMetrics(displayId: Int): Pair<Int, Int>

    /**
     * 执行全局动作（返回、Home、最近任务等，仅适用于设备物理主屏）。
     */
    fun performGlobalAction(action: Int): Boolean

    /**
     * 记录指定 displayId 产生 UI 显著无障碍事件（内容变更、窗口切换、滚动等）的时间戳。
     */
    fun recordUiEvent(displayId: Int) {}

    /**
     * 获取指定 displayId 上一次发生显著无障碍事件的时间（开机毫秒时间戳）。
     */
    fun getLastUiEventTime(displayId: Int): Long = 0L
}

/**
 * [AccessibilityDriver] 的系统默认实现，由主应用无障碍服务连接时注入生命周期。
 */
object SystemAccessibilityDriver : AccessibilityDriver {
    private const val TAG = "niki914_zafiro_SystemAccessibilityDriver"
    private const val GESTURE_TIMEOUT_MS = 5_000L
    private const val SCREENSHOT_TIMEOUT_MS = 5_000L

    @Volatile
    private var serviceRef: AccessibilityService? = null

    private val lastEventTimes = ConcurrentHashMap<Int, Long>()

    val serviceContext: Context? get() = serviceRef

    override val isConnected: Boolean get() = serviceRef != null

    fun attach(service: AccessibilityService) {
        serviceRef = service
        Logger.i(TAG, "AccessibilityService attached")
    }

    fun detach() {
        serviceRef = null
        lastEventTimes.clear()
        Logger.i(TAG, "AccessibilityService detached")
    }

    override fun recordUiEvent(displayId: Int) {
        val now = tryOrNull { SystemClock.elapsedRealtime() } ?: System.currentTimeMillis()
        lastEventTimes[displayId] = now
    }

    override fun getLastUiEventTime(displayId: Int): Long {
        return lastEventTimes[displayId] ?: 0L
    }

    override fun getDisplayMetrics(displayId: Int): Pair<Int, Int> {
        val service = serviceRef ?: return 1080 to 2400
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val dm = service.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val display = dm?.getDisplay(displayId)
            if (display != null) {
                val point = Point()
                @Suppress("DEPRECATION")
                display.getRealSize(point)
                if (point.x > 0 && point.y > 0) {
                    return point.x to point.y
                }
            }
        }
        val defaultMetrics = service.resources.displayMetrics
        return defaultMetrics.widthPixels to defaultMetrics.heightPixels
    }

    override fun getActiveWindowRoot(displayId: Int): AccessibilityNodeInfo? {
        val service = serviceRef ?: run {
            Logger.w(TAG, "getActiveWindowRoot failed: service not connected")
            return null
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windows = service.windowsOnAllDisplays.get(displayId)
            if (!windows.isNullOrEmpty()) {
                try {
                    val root = firstRoot(windows)
                    if (root != null) return root
                } finally {
                    recycleWindows(windows)
                }
            }
        }

        if (displayId == Display.DEFAULT_DISPLAY) {
            return service.rootInActiveWindow
        }

        Logger.w(TAG, "No active window found on displayId=$displayId")
        return null
    }

    override suspend fun dispatchGesture(
        displayId: Int,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        duration: Long,
    ): Boolean {
        val service = serviceRef ?: run {
            Logger.w(TAG, "dispatchGesture failed: service not connected")
            return false
        }

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val builder = GestureDescription.Builder().addStroke(stroke)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && displayId != Display.DEFAULT_DISPLAY) {
            builder.setDisplayId(displayId)
        }

        val gesture = builder.build()
        val result = withTimeoutOrNull(GESTURE_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val dispatched = service.dispatchGesture(
                    gesture,
                    object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            if (cont.isActive) cont.resume(false)
                        }
                    },
                    null,
                )
                if (!dispatched && cont.isActive) {
                    cont.resume(false)
                }
            }
        }
        return result ?: false
    }

    override suspend fun takeScreenshot(displayId: Int): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Logger.w(TAG, "takeScreenshot requires API 30+")
            return null
        }
        val service = serviceRef ?: run {
            Logger.w(TAG, "takeScreenshot failed: service not connected")
            return null
        }

        return withTimeoutOrNull(SCREENSHOT_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                service.takeScreenshot(
                    displayId,
                    service.mainExecutor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                            val buffer = result.hardwareBuffer
                            val bitmap = runCatchingCancellable {
                                Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                            }.getOrNull()
                            runCatchingCancellable { buffer.close() }
                            if (cont.isActive) cont.resume(bitmap)
                        }

                        override fun onFailure(errorCode: Int) {
                            Logger.w(TAG, "takeScreenshot failed on displayId=$displayId errorCode=$errorCode")
                            if (cont.isActive) cont.resume(null)
                        }
                    },
                )
            }
        }
    }

    override fun performNodeAction(
        node: AccessibilityNodeInfo,
        action: Int,
        text: String?,
    ): Boolean {
        return if (action == AccessibilityNodeInfo.ACTION_SET_TEXT && text != null) {
            val bundle = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            node.performAction(action, bundle)
        } else {
            node.performAction(action)
        }
    }

    override fun performGlobalAction(action: Int): Boolean {
        return serviceRef?.performGlobalAction(action) ?: false
    }

    private fun firstRoot(windows: List<AccessibilityWindowInfo>): AccessibilityNodeInfo? {
        val active = windows.firstOrNull { it.isActive }
        if (active != null) return active.root
        val focused = windows.firstOrNull { it.isFocused }
        if (focused != null) return focused.root
        for (window in windows) {
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = window.root ?: continue
            return root
        }
        for (window in windows) {
            val root = window.root ?: continue
            return root
        }
        return null
    }

    private fun recycleWindows(windows: List<AccessibilityWindowInfo>) {
        windows.forEach { window ->
            tryOrNull {
                @Suppress("DEPRECATION")
                window.recycle()
            }
        }
    }
}
