package com.niki914.nexus.agentic.chat.agentic.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK
import android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK
import android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
import android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
import android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT
import com.niki914.nexus.agentic.chat.agentic.accessibility.AccessibilityController.ensureService
import com.niki914.nexus.agentic.chat.agentic.accessibility.AccessibilityController.nodeCache
import com.niki914.nexus.agentic.chat.agentic.accessibility.AccessibilityController.refreshNodeCache
import com.niki914.nexus.agentic.chat.agentic.accessibility.AccessibilityController.serviceInstance
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolResult
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalCommandOutcome
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalOpenOutcome
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalSessionPool
import com.niki914.nexus.xposed.api.util.ContextProvider
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Accessibility service interaction controller.
 *
 * Manages the lifecycle of an [IAccessibility] service connection, provides
 * screen capture, node action execution, gesture dispatch, and key event
 * injection. Shell fallback (libterm: root > shizuku > user) is used when
 * the accessibility method fails or is unavailable.
 */
interface IAccessibility {
    val windowRoot: AccessibilityNodeInfo?
    fun performAction(node: AccessibilityNodeInfo, action: Int, text: String?): Boolean
    fun dispatchGesture(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        duration: Long
    ): Boolean
}

enum class NodeAction { CLICK, LONG_CLICK, SET_TEXT, SCROLL_FORWARD, SCROLL_BACKWARD }
enum class InteractionMethod { ACCESSIBILITY, SHELL }
data class ScreenSnapshot(val yaml: String, val nodeCount: Int)

object AccessibilityController {

    private var serviceInstance: IAccessibility? = null
    private val nodeCache = ConcurrentHashMap<Int, AccessibilityNodeInfo>()

    /** Set by the app module before any screen-interaction calls. */
    @Volatile
    var pointerOverlay: IPointerOverlay? = null

    private var pointerShown = false

    private var shellSessionHandle: String? = null
    private var shellIdentity: ShellIdentity = ShellIdentity.NONE
    private var accessibilitySettingsOpened: Boolean = false

    private enum class ShellIdentity { ROOT, SHIZUKU, USER, NONE }

    /** Reset pointer state and hide overlay at end of an agent turn. */
    fun onTurnEnd() {
        pointerShown = false
        pointerOverlay?.hide()
    }

    private data class ScreenContext(
        val root: AccessibilityNodeInfo,
        val widthPixels: Int,
        val heightPixels: Int,
        val appPackage: String,
    )

    private data class ShellResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    ) {
        val success: Boolean get() = exitCode == 0
    }

    fun setService(service: IAccessibility) {
        serviceInstance = service
    }

    fun clearService() {
        serviceInstance = null
        nodeCache.clear()
    }

    fun clearPointerOverlay() {
        pointerOverlay?.hide()
        pointerOverlay = null
        pointerShown = false
    }

    private suspend fun ensureShellSession(): ShellIdentity {
        if (shellIdentity != ShellIdentity.NONE) return shellIdentity

        for (identity in listOf("root", "shizuku", "user")) {
            when (val outcome = TerminalSessionPool.open(identity)) {
                is TerminalOpenOutcome.Success -> {
                    shellSessionHandle = outcome.session
                    shellIdentity = when (identity) {
                        "root" -> ShellIdentity.ROOT
                        "shizuku" -> ShellIdentity.SHIZUKU
                        else -> ShellIdentity.USER
                    }
                    return shellIdentity
                }
                else -> continue
            }
        }
        return ShellIdentity.NONE
    }

    private fun resetShellSession() {
        shellSessionHandle = null
        shellIdentity = ShellIdentity.NONE
    }

    /**
     * Ensures the accessibility service is connected.
     *
     * Tries root first, then shizuku, to enable the service via settings put secure.
     * If neither can write secure settings, opens the accessibility settings page
     * for manual setup (once per process lifetime).
     */
    suspend fun ensureService(): Result<Unit> {
        if (serviceInstance != null) return Result.success(Unit)

        val ctx = ContextProvider.await()
        val serviceName = "${ctx.packageName}/.mod.feat.NexusAccessibilityService"

        // Try root, then shizuku to enable the accessibility service.
        // Each attempt actually runs a command to verify the identity works
        // (not just that the session opened — a denied root prompt may still
        // produce a non-root shell that can't write settings).
        var canWriteSettings = false
        for (identity in listOf("root", "shizuku")) {
            when (val outcome = TerminalSessionPool.openAndExecute(
                identity = identity,
                cwd = null,
                command = "settings get secure enabled_accessibility_services",
                timeoutMs = 15_000L,
            )) {
                is TerminalCommandOutcome.Success -> {
                    val stdout = outcome.result.stdout.toByteArray().decodeToString().trim()
                    val existing = stdout
                        .takeUnless { it.isBlank() || it == "null" }
                        ?.split(":")
                        .orEmpty()
                        .filter { it.isNotBlank() }
                        .toMutableSet()
                    existing += serviceName
                    val newValue = existing.joinToString(":")
                    TerminalSessionPool.executeBlocking(
                        outcome.session,
                        "settings put secure enabled_accessibility_services $newValue",
                        10_000L,
                    )
                    TerminalSessionPool.executeBlocking(
                        outcome.session,
                        "settings put secure accessibility_enabled 1",
                        10_000L,
                    )
                    shellSessionHandle = outcome.session
                    shellIdentity = if (identity == "root") ShellIdentity.ROOT else ShellIdentity.SHIZUKU
                    canWriteSettings = true

                    // Grant overlay permission for pointer indicator
                    TerminalSessionPool.executeBlocking(
                        outcome.session,
                        "appops set ${ctx.packageName} SYSTEM_ALERT_WINDOW allow",
                        10_000L,
                    )
                    break
                }
                else -> continue
            }
        }

        if (!canWriteSettings) {
            ensureShellSession() // fall back to user shell for basic commands

            if (serviceInstance != null) {
                return Result.success(Unit) // user enabled it manually in a previous attempt
            }

            // Cannot enable automatically — open both settings pages and fail
            if (!accessibilitySettingsOpened) {
                accessibilitySettingsOpened = true
                try {
                    ctx.startActivity(
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    ctx.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:${ctx.packageName}")
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (_: Exception) {}
            }

            return Result.failure(RuntimeException(
                "Nexus cannot control this device because the Accessibility Service is not enabled, " +
                        "and neither root nor Shizuku is available to enable it automatically. " +
                        "Tell the user to open Settings > Accessibility and turn on 'Nexus' manually, " +
                        "then grant 'Display over other apps' permission."
            ))
        }

        // Give the system a moment to bind the service
        delay(200L)
        repeat(5) {
            if (serviceInstance != null) return Result.success(Unit)
            delay(200L)
        }

        return if (serviceInstance != null) {
            Result.success(Unit)
        } else {
            Result.failure(RuntimeException("AccessibilityService did not start within 1s"))
        }
    }

    /**
     * Captures the current screen's accessibility tree.
     *
     * 1. Calls [ensureService] (propagates failure).
     * 2. Obtains the root node from [IAccessibility.rootInActiveWindow].
     * 3. Reads display metrics via [Context] and the current foreground package.
     * 4. Formats the tree with [TreeFormatter.format] into YAML.
     * 5. Rebuilds [nodeCache] via DFS so indices match the YAML output.
     *
     * @return [ScreenSnapshot] containing the YAML string and node count, or failure.
     */
    suspend fun captureScreen(): Result<ScreenSnapshot> {
        val ctx = try {
            refreshNodeCache()
        } catch (e: Exception) {
            return Result.failure(e)
        }

        // First captureScreen of the session: reveal pointer at random centre-area position
        if (!pointerShown) {
            pointerShown = true
            pointerOverlay?.let { overlay ->
                val rx = ctx.widthPixels / 3f + Math.random().toFloat() * (ctx.widthPixels / 3f)
                val ry = ctx.heightPixels / 3f + Math.random().toFloat() * (ctx.heightPixels / 3f)
                overlay.show(rx, ry)
            }
        }

        val yaml = TreeFormatter.format(ctx.root, ctx.widthPixels, ctx.heightPixels, ctx.appPackage)

        if (nodeCache.size <= 1) {
            return Result.failure(
                RuntimeException(
                    "No accessibility nodes found in the current window. " +
                            "This app likely uses a non-native UI framework (Flutter, Unity, WebView, game engine) " +
                            "that does not expose standard Android accessibility node trees. " +
                            "screen_content, node_action, and gesture tools will not work with this app."
                )
            )
        }

        return Result.success(ScreenSnapshot(yaml, nodeCache.size))
    }

    /**
     * Refreshes the node cache and returns screen context information.
     *
     * 1. Calls [ensureService] (throws on failure).
     * 2. Obtains the root node from [IAccessibility.rootInActiveWindow].
     * 3. Reads display metrics via [Context] and the current foreground package.
     * 4. Rebuilds [nodeCache] via DFS so indices match TreeFormatter's allocation order.
     *
     * @return [ScreenContext] containing root node, display dimensions, and app package.
     * @throws RuntimeException if the service is unavailable or there is no active window.
     */
    private suspend fun refreshNodeCache(): ScreenContext {
        ensureService().getOrElse { throw it }

        val root = serviceInstance!!.windowRoot
            ?: throw RuntimeException("No active window")

        val ctx = ContextProvider.await()
        val dm = ctx.resources.displayMetrics
        val appPkg = root.packageName?.toString() ?: "unknown"

        rebuildCache(root)

        return ScreenContext(root, dm.widthPixels, dm.heightPixels, appPkg)
    }

    /**
     * Rebuilds [nodeCache] with a full DFS index of the entire [root] tree.
     *
     * Indices are assigned depth-first, parent-before-child, matching
     * [TreeFormatter]'s allocation order. The cache holds every node, even
     * those that may be pruned from the YAML output.
     */
    private fun rebuildCache(root: AccessibilityNodeInfo) {
        nodeCache.clear()
        val counter = AtomicInteger(0)
        indexTree(root, counter)
    }

    private fun indexTree(node: AccessibilityNodeInfo, counter: AtomicInteger) {
        val index = counter.getAndIncrement()
        nodeCache[index] = node
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { indexTree(it, counter) }
        }
    }

    /**
     * Searches the node cache for nodes matching the given keywords.
     *
     * If the cache is empty, triggers [refreshNodeCache] first.
     *
     * @param keywords List of keywords to match against node text and content description.
     * @param matchMode "all" requires every keyword to match; any other value matches any keyword.
     * @param limit Maximum number of results to return.
     * @return YAML-formatted search results with indices, types, bounds, and positions.
     */
    suspend fun searchNodes(
        keywords: List<String>,
        matchMode: String,
        limit: Int,
    ): Result<String> {
        try {
            ensureService().getOrElse { return Result.failure(it) }
        } catch (e: Exception) {
            return Result.failure(e)
        }

        // Always refresh the cache so search runs against the current screen,
        // not a previous screen_content call.
        try {
            refreshNodeCache()
        } catch (e: Exception) {
            return Result.failure(e)
        }

        val ctx = ContextProvider.await()
        val screenW = ctx.resources.displayMetrics.widthPixels
        val screenH = ctx.resources.displayMetrics.heightPixels

        val lowerKw = keywords.map { it.lowercase() }

        val matches = nodeCache.entries.mapNotNull { (index, node) ->
            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            val combined = "$text $desc".lowercase()

            val matched = when (matchMode) {
                "all" -> lowerKw.all { it in combined }
                else -> lowerKw.any { it in combined }
            }

            if (matched) index to node else null
        }

        val results = matches.take(limit)

        val sb = StringBuilder()
        sb.append("matched: ${results.size}")
        if (matches.size > limit) {
            sb.append(" # truncated: max_results($limit), total_hits: ${matches.size}")
        }
        sb.append("\nnodes:\n")

        for ((index, node) in results) {
            sb.append("  - ")
            sb.append(formatSearchResultNode(index, node, screenW, screenH))
            sb.append("\n")
        }

        return Result.success(sb.toString())
    }

    private fun formatSearchResultNode(
        index: Int,
        node: AccessibilityNodeInfo,
        screenW: Int,
        screenH: Int,
    ): String {
        val className = node.className?.toString() ?: ""
        val type = PruningRules.mapSemanticType(className, null)
        val text = PruningRules.normalizeText(node.text?.toString() ?: "")
        val desc = PruningRules.normalizeText(node.contentDescription?.toString() ?: "")

        val androidRect = android.graphics.Rect()
        node.getBoundsInScreen(androidRect)
        val bounds = Rect(androidRect.left, androidRect.top, androidRect.right, androidRect.bottom)
        val pos = PruningRules.posOf(bounds, screenW, screenH)

        val sb = StringBuilder()
        sb.append("{i: $index, t: ${type.name.lowercase()}, b: [${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}], pos: $pos")

        if (text.isNotEmpty()) {
            val quoted =
                if (PruningRules.needsQuoting(text)) "\"${text.replace("\"", "\\\"")}\"" else text
            sb.append(", txt: $quoted")
        }
        if (desc.isNotEmpty()) {
            val quoted =
                if (PruningRules.needsQuoting(desc)) "\"${desc.replace("\"", "\\\"")}\"" else desc
            sb.append(", h: $quoted")
        }

        if (node.isClickable) sb.append(", tap: true")
        if (node.isLongClickable) sb.append(", hold: true")
        if (node.isEditable) sb.append(", edit: true")
        if (node.isScrollable) sb.append(", scroll: true")
        if (node.isChecked) sb.append(", checked: true")

        sb.append("}")
        return sb.toString()
    }

    /**
     * Executes a node-level action (click, long-click, scroll, set-text).
     *
     * When [method] is [InteractionMethod.SHELL]:
     * - SET_TEXT is rejected (shell cannot type into views).
     * - All other actions use libterm shell commands.
     *
     * When [method] is [InteractionMethod.ACCESSIBILITY]:
     * - Delegates to [IAccessibility.performAction].
     * - On failure, non-SET_TEXT actions fall back to shell.
     * - SET_TEXT failure returns an error (no shell fallback).
     */
    suspend fun executeNodeAction(
        index: Int,
        action: NodeAction,
        text: String?,
        method: InteractionMethod,
    ): BuiltinToolResult {
        ensureService().getOrElse { e ->
            return BuiltinToolResult.failure(
                "SERVICE_UNAVAILABLE", e.message ?: "Service unavailable"
            )
        }

        if (method == InteractionMethod.SHELL && action == NodeAction.SET_TEXT) {
            return BuiltinToolResult.failure(
                "METHOD_NOT_SUPPORTED", "set_text requires accessibility method"
            )
        }

        val node = nodeCache[index]
            ?: return BuiltinToolResult.failure(
                "NODE_NOT_FOUND", "Node $index not found in cache"
            )

        // Fly pointer to node centre before acting
        val nodeRect = android.graphics.Rect()
        node.getBoundsInScreen(nodeRect)
        pointerOverlay?.animateTo(nodeRect.centerX().toFloat(), nodeRect.centerY().toFloat())

        return when (method) {
            InteractionMethod.SHELL -> executeShellAction(node, index, action)
            InteractionMethod.ACCESSIBILITY -> executeAccessibilityAction(node, index, action, text)
        }
    }

    private suspend fun executeShellAction(
        node: AccessibilityNodeInfo,
        index: Int,
        action: NodeAction,
    ): BuiltinToolResult {
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        val cx = rect.centerX()
        val cy = rect.centerY()

        return when (action) {
            NodeAction.CLICK, NodeAction.LONG_CLICK -> {
                // Pre-tap validation: refresh the node to check liveness,
                // bounds stability, and visibility. Shell taps are coordinate-
                // based, so a stale or shifted node causes a mis-tap.
                val oldVisible = node.isVisibleToUser

                val refreshed = node.refresh()
                if (!refreshed) {
                    return BuiltinToolResult.failure(
                        "NODE_STALE",
                        "Node $index no longer exists on screen. " +
                                "The UI changed since the last screen_content call — re-read and try again.",
                    )
                }

                val newRect = android.graphics.Rect()
                node.getBoundsInScreen(newRect)
                val newCx = newRect.centerX()
                val newCy = newRect.centerY()

                val dx = Math.abs(newCx - cx)
                val dy = Math.abs(newCy - cy)
                if (dx > 50 || dy > 50) {
                    return BuiltinToolResult.failure(
                        "UI_CHANGED",
                        "Node $index moved ${dx}x${dy}px since last screen_content. " +
                                "A layout shift may have occurred — re-read the screen.",
                    )
                }

                if (oldVisible && !node.isVisibleToUser) {
                    return BuiltinToolResult.failure(
                        "NODE_HIDDEN",
                        "Node $index is no longer visible (possibly covered). " +
                                "Re-read the screen and re-evaluate.",
                    )
                }

                val result = if (action == NodeAction.LONG_CLICK) {
                    runShellCommand("input swipe $newCx $newCy $newCx $newCy 1500")
                } else {
                    runShellCommand("input tap $newCx $newCy")
                }
                if (!result.success) {
                    return BuiltinToolResult.failure(
                        "SHELL_FAILED",
                        "Shell ${if (action == NodeAction.LONG_CLICK) "long tap" else "tap"} failed: ${result.stderr}",
                    )
                }
                val name = if (action == NodeAction.LONG_CLICK) "long tap" else "tap"
                BuiltinToolResult.success("shell $name at ($newCx, $newCy)")
            }

            NodeAction.SCROLL_FORWARD -> {
                val result = runShellCommand("input swipe $cx $cy $cx ${cy - 200} 300")
                if (!result.success) {
                    return BuiltinToolResult.failure(
                        "SHELL_FAILED", "Shell scroll forward failed: ${result.stderr}"
                    )
                }
                BuiltinToolResult.success("shell scroll forward at ($cx, $cy)")
            }

            NodeAction.SCROLL_BACKWARD -> {
                val result = runShellCommand("input swipe $cx $cy $cx ${cy + 200} 300")
                if (!result.success) {
                    return BuiltinToolResult.failure(
                        "SHELL_FAILED", "Shell scroll backward failed: ${result.stderr}"
                    )
                }
                BuiltinToolResult.success("shell scroll backward at ($cx, $cy)")
            }

            NodeAction.SET_TEXT -> {
                BuiltinToolResult.failure(
                    "METHOD_NOT_SUPPORTED", "set_text requires accessibility method"
                )
            }
        }
    }

    private suspend fun executeAccessibilityAction(
        node: AccessibilityNodeInfo,
        index: Int,
        action: NodeAction,
        text: String?,
    ): BuiltinToolResult {
        val actionInt = when (action) {
            NodeAction.CLICK -> ACTION_CLICK
            NodeAction.LONG_CLICK -> ACTION_LONG_CLICK
            NodeAction.SET_TEXT -> ACTION_SET_TEXT
            NodeAction.SCROLL_FORWARD -> ACTION_SCROLL_FORWARD
            NodeAction.SCROLL_BACKWARD -> ACTION_SCROLL_BACKWARD
        }

        val success = serviceInstance!!.performAction(node, actionInt, text)
        if (success) {
            return BuiltinToolResult.success("action ${action.name} performed via accessibility")
        }

        // Fallback to shell for non-SET_TEXT actions
        return if (action != NodeAction.SET_TEXT) {
            val shellResult = executeShellAction(node, index, action)
            if (shellResult.ok) {
                BuiltinToolResult.success("accessibility fallback: ${shellResult.message}")
            } else {
                shellResult
            }
        } else {
            BuiltinToolResult.failure(
                "SET_TEXT_FAILED",
                "set_text failed via accessibility, no shell fallback available"
            )
        }
    }

    /**
     * Executes a swipe gesture.
     *
     * - [InteractionMethod.ACCESSIBILITY]: dispatches via [IAccessibility.dispatchGesture].
     * - [InteractionMethod.SHELL]: uses libterm shell `input swipe ...`.
     */
    suspend fun executeGesture(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        duration: Long,
        method: InteractionMethod,
    ): BuiltinToolResult {
        ensureService().getOrElse { e ->
            return BuiltinToolResult.failure(
                "SERVICE_UNAVAILABLE", e.message ?: "Service unavailable"
            )
        }

        // Fly pointer along the swipe path before executing
        pointerOverlay?.showSwipe(startX, startY, endX, endY, duration)

        return when (method) {
            InteractionMethod.ACCESSIBILITY -> {
                val success =
                    serviceInstance?.dispatchGesture(startX, startY, endX, endY, duration) ?: false
                if (success) {
                    BuiltinToolResult.success("gesture performed via accessibility")
                } else {
                    BuiltinToolResult.failure("GESTURE_FAILED", "gesture failed via accessibility")
                }
            }

            InteractionMethod.SHELL -> {
                val result = runShellCommand("input swipe $startX $startY $endX $endY $duration")
                if (!result.success) {
                    return BuiltinToolResult.failure(
                        "SHELL_FAILED", "Shell gesture failed: ${result.stderr}"
                    )
                }
                BuiltinToolResult.success("gesture performed via shell")
            }
        }
    }

    /**
     * Executes a key event.
     *
     * Recognised key codes use [IAccessibility.performGlobalAction]:
     * - 4 (KEYCODE_BACK)      -> GLOBAL_ACTION_BACK
     * - 3 (KEYCODE_HOME)      -> GLOBAL_ACTION_HOME
     * - 187 (KEYCODE_APP_SWITCH) -> GLOBAL_ACTION_RECENTS
     * - 83 (KEYCODE_NOTIFICATION) -> GLOBAL_ACTION_NOTIFICATIONS
     * - 84 (KEYCODE_QUICK_SETTINGS) -> GLOBAL_ACTION_QUICK_SETTINGS
     *
     * Any other key code falls back to libterm shell `input keyevent <code>`.
     */
    suspend fun executeKeyEvent(keyCode: Int): BuiltinToolResult {
        ensureService().getOrElse { e ->
            return BuiltinToolResult.failure(
                "SERVICE_UNAVAILABLE", e.message ?: "Service unavailable"
            )
        }
        val actionId = when (keyCode) {
            4 -> GLOBAL_ACTION_BACK
            3 -> GLOBAL_ACTION_HOME
            187 -> GLOBAL_ACTION_RECENTS
            83 -> GLOBAL_ACTION_NOTIFICATIONS
            84 -> GLOBAL_ACTION_QUICK_SETTINGS
            else -> null
        }

        if (actionId != null) {
            val success =
                (serviceInstance as? AccessibilityService)?.performGlobalAction(actionId) ?: false
            val actionName = when (keyCode) {
                4 -> "BACK"
                3 -> "HOME"
                187 -> "APP_SWITCH"
                83 -> "NOTIFICATION"
                84 -> "QUICK_SETTINGS"
                else -> "UNKNOWN"
            }
            return if (success) {
                BuiltinToolResult.success("KEYCODE_$actionName performed")
            } else {
                BuiltinToolResult.failure(
                    "KEY_EVENT_FAILED", "${actionName} action failed"
                )
            }
        }

        val result = runShellCommand("input keyevent $keyCode")
        if (!result.success) {
            return BuiltinToolResult.failure(
                "SHELL_FAILED", "Shell keyevent $keyCode failed: ${result.stderr}"
            )
        }
        return BuiltinToolResult.success("shell keyevent $keyCode performed")
    }

    private suspend fun runShellCommand(command: String): ShellResult {
        val identity = ensureShellSession()
        val handle = shellSessionHandle
        if (identity == ShellIdentity.NONE || handle == null) {
            return ShellResult(-1, "", "No shell available (root, shizuku, and user all failed)")
        }

        return when (val outcome = TerminalSessionPool.executeBlocking(handle, command, 15_000L)) {
            is TerminalCommandOutcome.Success -> {
                ShellResult(
                    outcome.result.exitCode ?: -1,
                    outcome.result.stdout.toByteArray().decodeToString().trim(),
                    outcome.result.stderr.toByteArray().decodeToString().trim(),
                )
            }
            is TerminalCommandOutcome.Timeout -> {
                ShellResult(
                    -1,
                    outcome.result.stdout.toByteArray().decodeToString().trim(),
                    "Command timed out",
                )
            }
            is TerminalCommandOutcome.Failure -> {
                resetShellSession()
                ShellResult(-1, "", outcome.failure.message ?: "Shell command failed")
            }
            is TerminalCommandOutcome.SessionNotFound -> {
                resetShellSession()
                ShellResult(-1, "", "Shell session lost")
            }
            is TerminalCommandOutcome.Busy -> {
                ShellResult(-1, "", "Shell session busy")
            }
            is TerminalCommandOutcome.UnexpectedError -> {
                ShellResult(-1, "", outcome.throwable.message ?: "Unexpected shell error")
            }
        }
    }
}
