package com.niki914.zafiro.accessibility.port

import android.graphics.Bitmap
import android.graphics.Rect as AndroidRect
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import com.niki914.logging.Logger
import com.niki914.zafiro.api.util.runCatchingCancellable
import com.niki914.zafiro.api.util.tryOrNull
import com.niki914.zafiro.accessibility.driver.AccessibilityDriver
import com.niki914.zafiro.accessibility.driver.SystemAccessibilityDriver
import com.niki914.zafiro.accessibility.shell.ShellPointerDriver
import com.niki914.zafiro.accessibility.tree.DisplayNodeExtractor
import com.niki914.zafiro.api.screen.ActionStatus
import com.niki914.zafiro.api.screen.DisplayPort
import com.niki914.zafiro.api.screen.ImageFrame
import com.niki914.zafiro.api.screen.NodeAction
import com.niki914.zafiro.api.screen.NodeInfo
import com.niki914.zafiro.api.screen.PointerAction
import com.niki914.zafiro.api.screen.ScreenId
import com.niki914.zafiro.api.screen.ScreenImageUnavailable
import com.niki914.zafiro.api.screen.SemanticToken
import com.niki914.zafiro.api.screen.ShowStatus
import com.niki914.zafiro.api.screen.ShowTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * 基于 Android 无障碍服务的通用 [DisplayPort] 实现。
 *
 * 每一个 [A11yDisplayPort] 实例绑定唯一的 [displayId]，所有树遍历、手势注入与
 * 截图均通过该 ID 隔离路由，天然支持多屏并发。
 */
open class A11yDisplayPort(
    override val displayId: Int,
    protected val driver: AccessibilityDriver = SystemAccessibilityDriver,
    protected val shell: ShellPointerDriver = ShellPointerDriver(),
) : DisplayPort {

    private val rawNodeCache = ConcurrentHashMap<Int, AccessibilityNodeInfo>()

    @Volatile
    protected var lastYamlSnapshot: String = ""

    fun getDisplayMetrics(): Pair<Int, Int> = driver.getDisplayMetrics(displayId)

    private fun recycleOldNodes() {
        val oldNodes = rawNodeCache.values.toList()
        rawNodeCache.clear()
        oldNodes.forEach { node ->
            tryOrNull {
                @Suppress("DEPRECATION")
                node.recycle()
            }
        }
    }

    override suspend fun readNodes(): List<NodeInfo> = readNodes(version = "live")

    open suspend fun readNodes(version: String): List<NodeInfo> {
        var root = driver.getActiveWindowRoot(displayId)
        if (root == null) {
            // 给窗口切换/弹窗销毁留出最多 150ms 重新附着的时间
            for (i in 1..3) {
                delay(50L)
                root = driver.getActiveWindowRoot(displayId)
                if (root != null) break
            }
        }
        if (root == null) {
            Logger.w(TAG, "readNodes: No active window root on displayId=$displayId")
            recycleOldNodes()
            lastYamlSnapshot = ""
            return emptyList()
        }

        val (width, height) = driver.getDisplayMetrics(displayId)
        val appPkg = root.packageName?.toString().orEmpty()

        val extracted = DisplayNodeExtractor.extract(
            root = root,
            screenWidth = width,
            screenHeight = height,
            appPackage = appPkg,
            version = version,
        )

        recycleOldNodes()
        rawNodeCache.putAll(extracted.rawNodeMap)
        lastYamlSnapshot = extracted.yaml

        return extracted.flatNodes
    }

    /**
     * 等待 UI 界面进入稳定静止状态。
     *
     * 结合了「初始缓冲（Dwell）+ 采样哈希对比 + 事件静默期」的三重检测：
     * 1. 至少等待 [INITIAL_DWELL_MS]（200ms），让手势动作触发的转场动画启动、事件入队；
     * 2. 每隔 50ms 采样一次当前屏幕的结构哈希；
     * 3. 连续两次采样哈希一致，且该 display 过去 300ms 内未产生新无障碍事件，判定界面稳定并提前退出；
     * 4. 若超过 [timeoutMs] 则超时退出保底，防止无限动画导致流程卡死。
     */
    open suspend fun settle(timeoutMs: Long = DEFAULT_SETTLE_TIMEOUT_MS) {
        val startTime = uptime()
        val initialDwell = minOf(INITIAL_DWELL_MS, timeoutMs)
        if (initialDwell > 0) {
            delay(initialDwell)
        }

        var previousHash: Int? = computeTreeHash()
        var previousSampleTime = uptime()

        while (true) {
            val now = uptime()
            if (now - startTime >= timeoutMs) {
                Logger.d(TAG, "settle reached timeout (${timeoutMs}ms) on displayId=$displayId")
                break
            }
            delay(SETTLE_SAMPLE_INTERVAL_MS)
            val currentHash = computeTreeHash()
            val sampleTime = uptime()
            val lastEventTime = driver.getLastUiEventTime(displayId)

            if (currentHash != null && currentHash == previousHash &&
                lastEventTime <= previousSampleTime &&
                sampleTime - lastEventTime >= EVENT_QUIET_PERIOD_MS
            ) {
                Logger.d(TAG, "settle achieved on displayId=$displayId in ${sampleTime - startTime}ms")
                break
            }

            previousHash = currentHash
            previousSampleTime = sampleTime
        }
    }

    protected open fun computeTreeHash(): Int? {
        val root = driver.getActiveWindowRoot(displayId) ?: return null
        val (width, height) = driver.getDisplayMetrics(displayId)
        val appPkg = root.packageName?.toString().orEmpty()
        val extracted = DisplayNodeExtractor.extract(
            root = root,
            screenWidth = width,
            screenHeight = height,
            appPackage = appPkg,
            version = "settle",
        )
        val hash = extracted.flatNodes.hashCode()
        recycleNodes(extracted.rawNodeMap.values)
        return hash
    }

    private fun recycleNodes(nodes: Collection<AccessibilityNodeInfo>) {
        nodes.forEach { node ->
            tryOrNull {
                @Suppress("DEPRECATION")
                node.recycle()
            }
        }
    }

    private fun uptime(): Long = tryOrNull { SystemClock.elapsedRealtime() } ?: System.currentTimeMillis()

    override suspend fun frame(): ImageFrame {
        val bitmap = driver.takeScreenshot(displayId)
            ?: throw ScreenImageUnavailable("SCREENSHOT_FAILED", "Failed to capture screenshot on displayId=$displayId")

        val pngBytes = encodeBitmapToPng(bitmap)
            ?: throw ScreenImageUnavailable("CODEC_UNAVAILABLE", "Failed to encode screenshot bitmap on displayId=$displayId")

        return ImageFrame(
            screen = ScreenId(if (displayId == Display.DEFAULT_DISPLAY) "phone" else "vd-$displayId"),
            width = bitmap.width,
            height = bitmap.height,
            png = pngBytes,
        )
    }

    override suspend fun perform(
        token: SemanticToken,
        action: NodeAction,
        text: String?,
    ): ActionStatus {
        val node = rawNodeCache[token.index] ?: return ActionStatus.Failed("NODE_STALE")

        val success = when (action) {
            NodeAction.Click -> {
                if (node.isClickable && driver.performNodeAction(node, AccessibilityNodeInfo.ACTION_CLICK)) {
                    true
                } else {
                    // 无障碍节点不可点或点击失败，降级为中心坐标手势或 Shell 点击
                    val rect = AndroidRect()
                    node.getBoundsInScreen(rect)
                    val cx = rect.centerX().toFloat()
                    val cy = rect.centerY().toFloat()
                    driver.dispatchGesture(displayId, cx, cy, cx, cy, 100L) ||
                            shell.tap(displayId, rect.centerX(), rect.centerY())
                }
            }
            NodeAction.LongClick -> {
                if (node.isLongClickable && driver.performNodeAction(node, AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
                    true
                } else {
                    val rect = AndroidRect()
                    node.getBoundsInScreen(rect)
                    val cx = rect.centerX().toFloat()
                    val cy = rect.centerY().toFloat()
                    driver.dispatchGesture(displayId, cx, cy, cx, cy, 1500L) ||
                            shell.swipe(displayId, rect.centerX(), rect.centerY(), rect.centerX(), rect.centerY(), 1500L)
                }
            }
            NodeAction.SetText -> {
                driver.performNodeAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT, text)
            }
            NodeAction.ScrollForward -> {
                if (driver.performNodeAction(node, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                    true
                } else {
                    val rect = AndroidRect()
                    node.getBoundsInScreen(rect)
                    val cx = rect.centerX().toFloat()
                    val cy = rect.centerY().toFloat()
                    val targetY = (cy - 200).coerceAtLeast(0f)
                    driver.dispatchGesture(displayId, cx, cy, cx, targetY, 300L) ||
                            shell.swipe(displayId, rect.centerX(), rect.centerY(), rect.centerX(), targetY.toInt(), 300L)
                }
            }
            NodeAction.ScrollBackward -> {
                if (driver.performNodeAction(node, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
                    true
                } else {
                    val rect = AndroidRect()
                    node.getBoundsInScreen(rect)
                    val cx = rect.centerX().toFloat()
                    val cy = rect.centerY().toFloat()
                    val targetY = cy + 200
                    driver.dispatchGesture(displayId, cx, cy, cx, targetY, 300L) ||
                            shell.swipe(displayId, rect.centerX(), rect.centerY(), rect.centerX(), targetY.toInt(), 300L)
                }
            }
        }

        return if (success) ActionStatus.Done else ActionStatus.Failed("ACTION_FAILED")
    }

    override suspend fun inject(action: PointerAction): ActionStatus {
        val ok = when (action) {
            is PointerAction.Tap -> gestureOrShell(
                gesture = {
                    driver.dispatchGesture(
                        displayId,
                        action.x.toFloat(),
                        action.y.toFloat(),
                        action.x.toFloat(),
                        action.y.toFloat(),
                        100L,
                    )
                },
                shell = { shell.tap(displayId, action.x, action.y) },
            )
            is PointerAction.LongClick -> gestureOrShell(
                gesture = {
                    driver.dispatchGesture(
                        displayId,
                        action.x.toFloat(),
                        action.y.toFloat(),
                        action.x.toFloat(),
                        action.y.toFloat(),
                        1500L,
                    )
                },
                shell = {
                    shell.swipe(displayId, action.x, action.y, action.x, action.y, 1500L)
                },
            )
            is PointerAction.Swipe -> gestureOrShell(
                gesture = {
                    driver.dispatchGesture(
                        displayId,
                        action.x1.toFloat(),
                        action.y1.toFloat(),
                        action.x2.toFloat(),
                        action.y2.toFloat(),
                        action.durationMs,
                    )
                },
                shell = {
                    shell.swipe(displayId, action.x1, action.y1, action.x2, action.y2, action.durationMs)
                },
            )
            is PointerAction.Key -> {
                if (displayId != Display.DEFAULT_DISPLAY && action.code in GLOBAL_SYSTEM_KEYCODES) {
                    Logger.w(TAG, "Rejected global keycode ${action.code} on secondary displayId=$displayId")
                    return ActionStatus.Failed("UNSUPPORTED_GLOBAL_KEY_ON_VIRTUAL_SCREEN")
                }
                if (displayId == Display.DEFAULT_DISPLAY) {
                    val globalAction = when (action.code) {
                        4 -> 1 // GLOBAL_ACTION_BACK
                        3 -> 2 // GLOBAL_ACTION_HOME
                        187 -> 3 // GLOBAL_ACTION_RECENTS
                        83 -> 4 // GLOBAL_ACTION_NOTIFICATIONS
                        84 -> 5 // GLOBAL_ACTION_QUICK_SETTINGS
                        else -> null
                    }
                    if (globalAction != null && driver.performGlobalAction(globalAction)) {
                        return ActionStatus.Done
                    }
                }
                runCatchingCancellable {
                    shell.key(displayId, action.code)
                }.getOrElse { error ->
                    Logger.w(TAG, "shell.key failed for displayId=$displayId code=${action.code}", error)
                    false
                }
            }
        }
        return if (ok) ActionStatus.Done else ActionStatus.Failed("ACTION_FAILED")
    }

    private suspend fun gestureOrShell(
        gesture: suspend () -> Boolean,
        shell: suspend () -> Boolean,
    ): Boolean {
        if (gesture()) return true
        return shell()
    }

    override suspend fun show(target: ShowTarget): ShowStatus {
        // 应用启动（Intent/ActivityOptions）由外层业务或 Screen 门面执行，底层硬件驱动返回不支持
        return ShowStatus.Failed("NOT_SUPPORTED_ON_PORT")
    }

    fun latestYaml(): String = lastYamlSnapshot

    private suspend fun encodeBitmapToPng(bitmap: Bitmap): ByteArray? = withContext(Dispatchers.Default) {
        runCatchingCancellable {
            val software = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return@runCatchingCancellable null
            try {
                ByteArrayOutputStream().use { out ->
                    if (!software.compress(Bitmap.CompressFormat.PNG, 100, out)) return@runCatchingCancellable null
                    out.toByteArray()
                }
            } finally {
                software.recycle()
                bitmap.recycle()
            }
        }.onFailure { error ->
            Logger.w(TAG, "encodeBitmapToPng failed: ${error.message}")
        }.getOrNull()
    }

    companion object {
        private const val TAG = "niki914_zafiro_A11yDisplayPort"
        const val DEFAULT_SETTLE_TIMEOUT_MS = 2_000L
        const val INITIAL_DWELL_MS = 200L
        const val SETTLE_SAMPLE_INTERVAL_MS = 50L
        const val EVENT_QUIET_PERIOD_MS = 300L

        /**
         * 针对副屏/虚拟屏幕做 Fast-Fail 拦截的系统全局按键黑名单。
         *
         * 数据来源与 AOSP 架构机制依据：
         * 1. KEYCODE_HOME (3):
         *    - AOSP 拦截点：`PhoneWindowManager.java#interceptKeyBeforeDispatching` 委派给
         *      `DisplayHomeButtonHandler.java#handleHomeButton`，调用 `launchHomeFromHotKey`。
         *    - 根因：`RootWindowContainer.java#startHomeOnTaskDisplayArea` 要求 display 必须具备
         *      `supportsSystemDecorations()` 才能承载 Home。虚拟副屏默认无系统桌面，系统在回退机制中
         *      会路由至 `DEFAULT_DISPLAY`，导致实体手机主屏退出当前前台 App 回到桌面。
         * 2. KEYCODE_APP_SWITCH (187) 与 KEYCODE_RECENT_APPS (312):
         *    - AOSP 拦截点：`PhoneWindowManager.java` 调用 `IStatusBarService.toggleRecentApps()`。
         *    - 根因：多任务/最近任务界面属于 SystemUI 进程且仅在 `DEFAULT_DISPLAY` 上展示，虚拟屏无任务栈总览。
         * 3. KEYCODE_NOTIFICATION (83) 与 KEYCODE_QUICK_SETTINGS (84):
         *    - AOSP 拦截点：`IStatusBarService.expandNotificationsPanel()` / `expandSettingsPanel()`。
         *    - 根因：通知抽屉与快捷设置面板是默认主屏专属系统级 UI。
         * 4. KEYCODE_POWER (26), KEYCODE_SLEEP (223), KEYCODE_WAKEUP (224), KEYCODE_SOFT_SLEEP (276):
         *    - AOSP 拦截点：`PhoneWindowManager.java#interceptKeyBeforeQueueing` 拦截整机电源与睡眠状态。
         *    - 根因：电源键作用于整机硬件状态，不能在副屏中模拟。
         * 5. KEYCODE_ASSIST (219) 与 KEYCODE_VOICE_ASSIST (231):
         *    - AOSP 拦截点：`AssistUtils.launchAssistSession()`。
         *    - 根因：语音助手会话与窗口仅在主屏绑定。
         * 6. KEYCODE_VOLUME_UP (24), KEYCODE_VOLUME_DOWN (25), KEYCODE_VOLUME_MUTE (164):
         *    - AOSP 拦截点：`AudioService` 全局音量流调节，无法被单独屏幕作用域隔离。
         */
        val GLOBAL_SYSTEM_KEYCODES: Set<Int> = setOf(
            3,   // KEYCODE_HOME
            187, // KEYCODE_APP_SWITCH
            312, // KEYCODE_RECENT_APPS
            83,  // KEYCODE_NOTIFICATION
            84,  // KEYCODE_QUICK_SETTINGS
            26,  // KEYCODE_POWER
            223, // KEYCODE_SLEEP
            224, // KEYCODE_WAKEUP
            276, // KEYCODE_SOFT_SLEEP
            219, // KEYCODE_ASSIST
            231, // KEYCODE_VOICE_ASSIST
            24,  // KEYCODE_VOLUME_UP
            25,  // KEYCODE_VOLUME_DOWN
            164, // KEYCODE_VOLUME_MUTE
        )
    }
}
