package com.niki914.zafiro.accessibility.port

import android.graphics.Bitmap
import android.graphics.Rect as AndroidRect
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
        val root = driver.getActiveWindowRoot(displayId) ?: run {
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
                shell.key(displayId, action.code)
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
    }
}
