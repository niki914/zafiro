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
import com.niki914.nexus.agentic.chat.agentic.buildin.ScreenOperationError
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalCommandOutcome
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalOpenOutcome
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalSessionPool
import com.niki914.nexus.xposed.api.util.ContextProvider
import android.os.SystemClock
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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
data class ScreenSnapshot(val yaml: String, val version: String, val nodeCount: Int)

object AccessibilityController {

    private var serviceInstance: IAccessibility? = null
    private val nodeCache = ConcurrentHashMap<Int, AccessibilityNodeInfo>()

    /** Current version string for screen snapshot staleness tracking. */
    @Volatile
    var currentVersion: String = ""
        private set

    /** Timestamp of the last UI-significant accessibility event (ms, [SystemClock.elapsedRealtime]). */
    @Volatile
    var lastUiEventTime: Long = 0L
        private set

    /**
     * Monotonic generation counter, advanced only by strong UI events
     * (window/text/scroll/checked changes). Sampled together with each stable
     * sample; any change invalidates an in-progress semantic-stability run.
     */
    private val strongUiEventGeneration = AtomicLong(0L)

    val currentStrongUiEventGeneration: Long get() = strongUiEventGeneration.get()

    /** Set by the app module before any screen-interaction calls. */
    @Volatile
    var pointerOverlay: IPointerOverlay? = null

    private var pointerShown = false
    private var cachedScreenWidth: Int = 0
    private var cachedScreenHeight: Int = 0

    private var shellSessionHandle: String? = null
    private var shellIdentity: ShellIdentity = ShellIdentity.NONE
    private var accessibilitySettingsOpened: Boolean = false

    private enum class ShellIdentity { ROOT, SHIZUKU, USER, NONE }

    /** Reset pointer state and hide overlay at end of an agent turn. */
    fun onTurnEnd() {
        pointerShown = false
        pointerOverlay?.hide()
    }

    /** Reveal pointer overlay at a random centre-area position, once per agent turn. */
    fun ensurePointerShown() {
        if (pointerShown) return
        pointerShown = true
        pointerOverlay?.let { overlay ->
            val w = if (cachedScreenWidth > 0) cachedScreenWidth else 1080
            val h = if (cachedScreenHeight > 0) cachedScreenHeight else 2400
            val rx = w / 3f + Math.random().toFloat() * (w / 3f)
            val ry = h / 3f + Math.random().toFloat() * (h / 3f)
            overlay.show(rx, ry)
        }
    }

    private val versionRng = java.security.SecureRandom()

    /** Generates a random 12-character hex version string for screen snapshot tracking. */
    private fun nextVersion(): String {
        val bytes = ByteArray(6)
        versionRng.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
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
        val shellAvailable: Boolean = true,
        val errorCode: String? = null,
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

    /**
     * Called from [NexusAccessibilityService.onAccessibilityEvent] for every
     * filtered accessibility event: refreshes [lastUiEventTime] for all events,
     * but advances the strong-event generation only for strong ones.
     */
    fun recordUiEvent(
        significance: UiEventSignificance,
        eventType: Int = 0,
        contentChangeTypes: Int = 0,
    ) {
        lastUiEventTime = SystemClock.elapsedRealtime()
        if (significance == UiEventSignificance.STRONG) {
            strongUiEventGeneration.incrementAndGet()
            lastStrongEventType = eventType
            lastStrongContentChangeTypes = contentChangeTypes
        }
    }

    // Temporary diagnostic (QA): last strong event details for timeout
    // root-cause logging. Delete together with the QA block.
    @Volatile
    var lastStrongEventType: Int = 0
        private set

    @Volatile
    var lastStrongContentChangeTypes: Int = 0
        private set

    fun clearPointerOverlay() {
        pointerOverlay?.dispose()
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
        // Each attempt runs a real command and checks exit codes — a
        // TerminalCommandOutcome.Success only means the terminal returned,
        // not that the command succeeded. A denied root prompt may produce
        // a non-root shell whose settings commands return non-zero.
        var canWriteSettings = false
        for (identity in listOf("root", "shizuku")) {
            val openOutcome = TerminalSessionPool.openAndExecute(
                identity = identity,
                cwd = null,
                command = "settings get secure enabled_accessibility_services",
                timeoutMs = 15_000L,
            )
            if (openOutcome !is TerminalCommandOutcome.Success) continue
            val getExitCode = openOutcome.result.exitCode ?: -1
            if (getExitCode != 0) {
                TerminalSessionPool.close(openOutcome.session)
                continue
            }

            val stdout = openOutcome.result.stdout.toByteArray().decodeToString().trim()
            val existing = stdout
                .takeUnless { it.isBlank() || it == "null" }
                ?.split(":")
                .orEmpty()
                .filter { it.isNotBlank() }
                .toMutableSet()
            existing += serviceName
            val newValue = existing.joinToString(":")

            val putServices = TerminalSessionPool.executeBlocking(
                openOutcome.session,
                "settings put secure enabled_accessibility_services $newValue",
                10_000L,
            )
            if (putServices !is TerminalCommandOutcome.Success || (putServices.result.exitCode ?: -1) != 0) {
                TerminalSessionPool.close(openOutcome.session)
                continue
            }

            val putEnabled = TerminalSessionPool.executeBlocking(
                openOutcome.session,
                "settings put secure accessibility_enabled 1",
                10_000L,
            )
            if (putEnabled !is TerminalCommandOutcome.Success || (putEnabled.result.exitCode ?: -1) != 0) {
                TerminalSessionPool.close(openOutcome.session)
                continue
            }

            shellSessionHandle = openOutcome.session
            shellIdentity = if (identity == "root") ShellIdentity.ROOT else ShellIdentity.SHIZUKU
            canWriteSettings = true

            // Grant overlay permission for pointer indicator (best-effort)
            TerminalSessionPool.executeBlocking(
                openOutcome.session,
                "appops set ${ctx.packageName} SYSTEM_ALERT_WINDOW allow",
                10_000L,
            )
            break
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
        delay(300L)
        repeat(9) {
            if (serviceInstance != null) return Result.success(Unit)
            delay(300L)
        }

        return if (serviceInstance != null) {
            Result.success(Unit)
        } else {
            Result.failure(RuntimeException("AccessibilityService did not start within 3s"))
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
            if (e is kotlinx.coroutines.CancellationException) throw e
            return Result.failure(e)
        }

        ensurePointerShown()

        val yaml = TreeFormatter.format(ctx.root, ctx.widthPixels, ctx.heightPixels, ctx.appPackage, currentVersion).yaml

        if (nodeCache.size <= 1) {
            return Result.failure(
                RuntimeException(
                    "No accessibility nodes found in the current window. " +
                            "This app likely uses a non-native UI framework (Flutter, Unity, WebView, game engine) " +
                            "that does not expose standard Android accessibility node trees. " +
                            "screen_operation_accessibility tool will not work with this app."
                )
            )
        }

        return Result.success(ScreenSnapshot(yaml, currentVersion, nodeCache.size))
    }

    /**
     * Captures the current screen's accessibility tree after an optional delay.
     *
     * If [delayMs] is greater than 0, waits for that many milliseconds before capturing,
     * allowing UI transitions to settle after a write operation.
     */
    suspend fun captureScreenAfterDelay(delayMs: Long): Result<ScreenSnapshot> {
        if (delayMs > 0) delay(delayMs)
        return captureScreen()
    }

    private data class StableSample(
        val snapshot: ScreenSnapshot,
        val semanticFingerprint: Long,
        val sampleTimeMs: Long,
        val strongEventGeneration: Long,
        // Temporary diagnostic (QA): per-class aggregated signature for
        // timeout root-cause logging. Delete together with the QA block.
        val classSignatures: Map<String, ClassSig> = emptyMap(),
        // Temporary diagnostic (QA): per-channel fingerprint components.
        val channels: DebugChannels? = null,
    )

    private const val MIN_DWELL_MS = 200L
    private const val POLL_INTERVAL_MS = 50L
    private const val EVENT_IDLE_MS = 300L

    /**
     * Waits for the UI to settle after a write operation via dual exit channels:
     *
     * - Channel A (event idle): the previous and current samples share a
     *   semantic fingerprint, no accessibility event arrived between the two
     *   samples, and the event stream has been idle for >= 300ms.
     * - Channel B (semantic stable): [UiStabilityTracker] reports
     *   SEMANTIC_STABLE (3 consecutive identical fingerprints across >= 150ms),
     *   tolerating weak accessibility event noise.
     *
     * Both channels re-check the strong-event generation before returning, and
     * both return the sample that was judged stable (no second capture).
     * If [settleTimeoutMs] is exceeded, returns the last successful sample with
     * a timeout note header.
     */
    suspend fun waitForStable(settleTimeoutMs: Long): Result<ScreenSnapshot> {
        val startTime = SystemClock.elapsedRealtime()
        val deadline = startTime + settleTimeoutMs

        val dwellRemaining = deadline - SystemClock.elapsedRealtime()
        if (dwellRemaining > 0) delay(minOf(MIN_DWELL_MS, dwellRemaining))

        val tracker = UiStabilityTracker()
        var previous: StableSample? = null
        var sampleCount = 0
        // Temporary diagnostic (QA): per-class change counters for timeout
        // root-cause logging. Delete together with the QA block.
        val classChangeCounts = HashMap<String, Int>()
        val classChangeFields = HashMap<String, MutableSet<String>>()
        val channelChangeCounts = HashMap<String, Int>()
        var genChangeCount = 0

        while (true) {
            val sample = captureStableSample()
            sampleCount += 1
            val now = sample.sampleTimeMs

            // Temporary diagnostic (QA): count strong-generation changes
            // between adjacent samples.
            if (previous != null && sample.strongEventGeneration != previous.strongEventGeneration) {
                genChangeCount += 1
            }

            // Temporary diagnostic (QA): accumulate per-channel changes.
            val prevCh = previous?.channels
            val curCh = sample.channels
            if (prevCh != null && curCh != null) {
                if (prevCh.structure != curCh.structure) channelChangeCounts["struct"] = (channelChangeCounts["struct"] ?: 0) + 1
                if (prevCh.text != curCh.text) channelChangeCounts["text"] = (channelChangeCounts["text"] ?: 0) + 1
                if (prevCh.offscreen != curCh.offscreen) channelChangeCounts["offscreen"] = (channelChangeCounts["offscreen"] ?: 0) + 1
                if (prevCh.bounds != curCh.bounds) channelChangeCounts["bounds"] = (channelChangeCounts["bounds"] ?: 0) + 1
                if (prevCh.flags != curCh.flags) channelChangeCounts["flags"] = (channelChangeCounts["flags"] ?: 0) + 1
                if (prevCh.truncation != curCh.truncation) channelChangeCounts["trunc"] = (channelChangeCounts["trunc"] ?: 0) + 1
            }

            // Temporary diagnostic (QA): accumulate per-class signature changes.
            val prevSigs = previous?.classSignatures
            if (prevSigs != null) {
                val allClasses = sample.classSignatures.keys + prevSigs.keys
                for (cn in allClasses) {
                    val cur = sample.classSignatures[cn]
                    val prev = prevSigs[cn]
                    if (cur != prev) {
                        classChangeCounts[cn] = (classChangeCounts[cn] ?: 0) + 1
                        val fields = classChangeFields[cn] ?: mutableSetOf<String>()
                        if (prev == null || cur == null) {
                            fields.add("node-count")
                        } else {
                            if (prev.textHash != cur.textHash) fields.add("text")
                            if (prev.boundsHash != cur.boundsHash) fields.add("bounds")
                            if (prev.flagsHash != cur.flagsHash) fields.add("flags")
                        }
                        classChangeFields[cn] = fields
                    }
                }
            }

            if (now >= deadline) {
                logSettleResult(StableExitReason.TIMEOUT, now - startTime, sampleCount)
                val channelsStr = channelChangeCounts.entries
                    .sortedByDescending { it.value }
                    .joinToString(" ") { (name, count) -> "$name=$count" }
                android.util.Log.d(
                    "NexusStable",
                    "timeout channels: $channelsStr",
                )
                val lastStrongTypeName = when (lastStrongEventType) {
                    32 -> "WINDOW_STATE_CHANGED"
                    2048 -> "WINDOW_CONTENT_CHANGED"
                    4194304 -> "WINDOWS_CHANGED"
                    4096 -> "VIEW_SCROLLED"
                    16 -> "VIEW_TEXT_CHANGED"
                    else -> lastStrongEventType.toString()
                }
                android.util.Log.d(
                    "NexusStable",
                    "timeout genChanges=$genChangeCount lastStrongEvent=$lastStrongTypeName(0x${lastStrongContentChangeTypes.toString(16)})",
                )
                val topChangers = classChangeCounts.entries
                    .sortedByDescending { it.value }
                    .take(3)
                    .joinToString(", ") { (cn, count) ->
                        val fields = classChangeFields[cn]?.sorted()?.joinToString("/") ?: ""
                        "$cn($count)[$fields]"
                    }
                android.util.Log.d(
                    "NexusStable",
                    "timeout topClassChanges: $topChangers",
                )
                return Result.success(sample.snapshot.copy(yaml = buildString {
                    appendLine("# Note: settle timed out after ${settleTimeoutMs}ms")
                    append(sample.snapshot.yaml)
                }))
            }

            val prev = previous
            if (prev != null
                && sample.semanticFingerprint == prev.semanticFingerprint
                && lastUiEventTime <= prev.sampleTimeMs
                && now - lastUiEventTime >= EVENT_IDLE_MS
                && currentStrongUiEventGeneration == sample.strongEventGeneration
            ) {
                logSettleResult(StableExitReason.EVENT_IDLE, now - startTime, sampleCount)
                return Result.success(sample.snapshot)
            }

            val decision = tracker.addSample(
                sample.semanticFingerprint, now, sample.strongEventGeneration,
            )
            if (decision == StabilityDecision.SEMANTIC_STABLE
                && (currentStrongUiEventGeneration == sample.strongEventGeneration
                    || tracker.noiseToleranceActive())
            ) {
                logSettleResult(StableExitReason.SEMANTIC_STABLE, now - startTime, sampleCount)
                return Result.success(sample.snapshot)
            }

            previous = sample
            delay(POLL_INTERVAL_MS)
        }
    }

    /**
     * Builds one stable-wait sample: refreshes the node cache, formats the
     * pruned tree into YAML + semantic fingerprint in one pass, and records
     * the sample time and strong-event generation.
     *
     * Sampling failures propagate to the caller unchanged (the existing
     * [refreshNodeCache] exception protocol is preserved).
     */
    private suspend fun captureStableSample(): StableSample {
        val ctx = refreshNodeCache()
        ensurePointerShown()
        val formatted = TreeFormatter.format(
            ctx.root, ctx.widthPixels, ctx.heightPixels, ctx.appPackage, currentVersion,
        )
        val snapshot = ScreenSnapshot(formatted.yaml, currentVersion, nodeCache.size)
        return StableSample(
            snapshot = snapshot,
            semanticFingerprint = formatted.semanticFingerprint,
            sampleTimeMs = SystemClock.elapsedRealtime(),
            strongEventGeneration = strongUiEventGeneration.get(),
            classSignatures = sampleClassSignatures(ctx),
            channels = computeDebugChannels(ctx),
        )
    }

    // Temporary diagnostic (QA): per-channel fingerprint components over the
    // raw tree, mirroring the fingerprint's mix-in fields so timeout logging
    // can show which semantic channel keeps changing (structure/order/type,
    // text, offscreen summaries, bounds, flags, truncation). Delete together
    // with the QA block.
    private data class DebugChannels(
        val structure: Long,
        val text: Long,
        val offscreen: Long,
        val bounds: Long,
        val flags: Long,
        val truncation: Long,
    )

    private fun computeDebugChannels(ctx: ScreenContext): DebugChannels {
        var structure = 0L
        var text = 0L
        var offscreen = 0L
        var bounds = 0L
        var flags = 0L
        var nodeCount = 0
        var maxDepth = 0

        fun walk(node: AccessibilityNodeInfo, parentType: SemanticType?, depth: Int) {
            nodeCount += 1
            if (depth > maxDepth) maxDepth = depth
            val cn = node.className?.toString() ?: ""
            val type = PruningRules.mapSemanticType(cn, parentType)
            structure = structure * 31 + type.name.hashCode()
            structure = structure * 31 + nodeCount
            val nodeText = node.text?.toString().orEmpty()
            val nodeDesc = node.contentDescription?.toString().orEmpty()
            text = text * 31 + nodeText.hashCode()
            text = text * 31 + nodeDesc.hashCode()
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            bounds = bounds * 31 + rect.left
            bounds = bounds * 31 + rect.top
            bounds = bounds * 31 + rect.right
            bounds = bounds * 31 + rect.bottom
            flags = flags * 31 + node.isClickable.hashCode()
            flags = flags * 31 + node.isLongClickable.hashCode()
            flags = flags * 31 + node.isEditable.hashCode()
            flags = flags * 31 + node.isScrollable.hashCode()
            flags = flags * 31 + node.isChecked.hashCode()
            if (node.isScrollable) {
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    val childRect = android.graphics.Rect()
                    child.getBoundsInScreen(childRect)
                    if (childRect.bottom <= 0 || childRect.top >= ctx.heightPixels) {
                        offscreen = offscreen * 31 + (child.text?.toString().orEmpty().hashCode())
                        offscreen = offscreen * 31 + (child.contentDescription?.toString().orEmpty().hashCode())
                    }
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { walk(it, type, depth + 1) }
            }
        }

        walk(ctx.root, null, 0)
        val truncation = (if (nodeCount >= 200) 1L else 0L) * 31 + (if (maxDepth > 20) 1L else 0L)
        return DebugChannels(structure, text, offscreen, bounds, flags, truncation)
    }

    // Temporary diagnostic (QA): aggregate per-class signature over the raw
    // tree so timeout logging can show which class keeps changing and in
    // which field channel. Delete together with the QA block.
    private data class ClassSig(
        val textHash: Long,
        val boundsHash: Long,
        val flagsHash: Long,
    )

    private fun sampleClassSignatures(ctx: ScreenContext): Map<String, ClassSig> {
        val byClass = HashMap<String, ClassSig>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(ctx.root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            val className = node.className?.toString().orEmpty()
            if (className.isNotEmpty()) {
                val prev = byClass[className]
                var text = prev?.textHash ?: 0L
                var bounds = prev?.boundsHash ?: 0L
                var flags = prev?.flagsHash ?: 0L
                text = text * 31 + node.text?.toString().orEmpty().hashCode()
                text = text * 31 + node.contentDescription?.toString().orEmpty().hashCode()
                text = text * 31 + node.childCount
                val rect = android.graphics.Rect()
                node.getBoundsInScreen(rect)
                bounds = bounds * 31 + rect.left
                bounds = bounds * 31 + rect.top
                bounds = bounds * 31 + rect.right
                bounds = bounds * 31 + rect.bottom
                flags = flags * 31 + node.isClickable.hashCode()
                flags = flags * 31 + node.isLongClickable.hashCode()
                flags = flags * 31 + node.isEditable.hashCode()
                flags = flags * 31 + node.isScrollable.hashCode()
                flags = flags * 31 + node.isChecked.hashCode()
                byClass[className] = ClassSig(text, bounds, flags)
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.add(it) }
            }
        }
        return byClass
    }

    // Temporary diagnostic log (F-09): delete this entire block after QA
    // verification — remove this function definition and the 3 standalone call
    // lines in waitForStable (EVENT_IDLE / SEMANTIC_STABLE / TIMEOUT branches).
    // No other logic is affected.
    private fun logSettleResult(exitReason: StableExitReason, elapsedMs: Long, sampleCount: Int) {
        android.util.Log.d(
            "NexusStable",
            "waitForStable elapsedMs=$elapsedMs exitReason=$exitReason sampleCount=$sampleCount",
        )
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
        cachedScreenWidth = dm.widthPixels
        cachedScreenHeight = dm.heightPixels
        val appPkg = root.packageName?.toString() ?: "unknown"

        rebuildCache(root)

        currentVersion = nextVersion()
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
            if (e is kotlinx.coroutines.CancellationException) throw e
            return Result.failure(e)
        }

        // Always refresh the cache so search runs against the current screen,
        // not a previous screen_content call.
        try {
            refreshNodeCache()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
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
        sb.append("\nversion: \"$currentVersion\"")
        sb.append("\nnodes:\n")

        for ((index, node) in results) {
            sb.append("  - ")
            sb.append(formatSearchResultNode(index, node, screenW, screenH, currentVersion))
            sb.append("\n")
        }

        return Result.success(sb.toString())
    }

    private fun formatSearchResultNode(
        index: Int,
        node: AccessibilityNodeInfo,
        screenW: Int,
        screenH: Int,
        version: String,
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
     * Parses the [token] to extract version and index, validates the version
     * against [currentVersion], and performs the action via accessibility
     * with automatic shell fallback for non-SET_TEXT actions.
     */
    suspend fun executeNodeAction(
        token: String,
        action: NodeAction,
        text: String?,
    ): BuiltinToolResult {
        ensureService().getOrElse { e ->
            return BuiltinToolResult.failure(
                "SERVICE_UNAVAILABLE", e.message ?: "Service unavailable"
            )
        }

        val st = SemanticToken.parse(token).getOrElse {
            return BuiltinToolResult.failure(
                "INVALID_ARGUMENTS",
                "Invalid token format: '$token'. " +
                    "Token must be assembled as {version}_{i} — join the snapshot version " +
                    "(from the YAML header) with the node's index (i field), separated by underscore. " +
                    "Example: version \"a3f2c91e7b40\" + node {i: 42, ...} → token \"a3f2c91e7b40_42\"."
            )
        }

        if (st.version != currentVersion) {
            return BuiltinToolResult.failure(
                "VERSION_MISMATCH",
                "Token version '${st.version}' does not match current version '$currentVersion'. " +
                        "The snapshot has been refreshed — use screen_operation_accessibility " +
                        "with read or search to get a fresh version and indices, " +
                        "then assemble new tokens as {version}_{i}."
            )
        }

        val node = nodeCache[st.index]
            ?: return BuiltinToolResult.failure(
                "NODE_NOT_FOUND", "Node ${st.index} not found in cache"
            )

        // Fly pointer to node centre before acting
        val nodeRect = android.graphics.Rect()
        node.getBoundsInScreen(nodeRect)
        pointerOverlay?.animateTo(nodeRect.centerX().toFloat(), nodeRect.centerY().toFloat())

        return executeAccessibilityAction(node, st.index, action, text)
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
                        result.errorCode ?: ScreenOperationError.SHELL_FAILED.code,
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
                        result.errorCode ?: ScreenOperationError.SHELL_FAILED.code,
                        "Shell scroll forward failed: ${result.stderr}",
                    )
                }
                BuiltinToolResult.success("shell scroll forward at ($cx, $cy)")
            }

            NodeAction.SCROLL_BACKWARD -> {
                val result = runShellCommand("input swipe $cx $cy $cx ${cy + 200} 300")
                if (!result.success) {
                    return BuiltinToolResult.failure(
                        result.errorCode ?: ScreenOperationError.SHELL_FAILED.code,
                        "Shell scroll backward failed: ${result.stderr}",
                    )
                }
                BuiltinToolResult.success("shell scroll backward at ($cx, $cy)")
            }

            NodeAction.SET_TEXT -> {
                BuiltinToolResult.failure(
                    "SET_TEXT_FAILED", "set_text requires accessibility method"
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
     * Executes a swipe gesture via accessibility dispatch.
     */
    suspend fun executeGesture(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        duration: Long,
    ): BuiltinToolResult {
        ensureService().getOrElse { e ->
            return BuiltinToolResult.failure(
                "SERVICE_UNAVAILABLE", e.message ?: "Service unavailable"
            )
        }

        // Fly pointer along the swipe path before executing
        pointerOverlay?.showSwipe(startX, startY, endX, endY, duration)

        val success =
            serviceInstance?.dispatchGesture(startX, startY, endX, endY, duration) ?: false
        return if (success) {
            BuiltinToolResult.success("gesture performed via accessibility")
        } else {
            BuiltinToolResult.failure("GESTURE_FAILED", "gesture failed via accessibility")
        }
    }

    /**
     * Executes a tap at the given screen coordinates via shell command.
     */
    suspend fun executeShellTap(x: Int, y: Int): BuiltinToolResult {
        ensureService().getOrElse { e ->
            return BuiltinToolResult.failure(
                "SERVICE_UNAVAILABLE", e.message ?: "Service unavailable"
            )
        }
        val result = runShellCommand("input tap $x $y")
        if (!result.shellAvailable) {
            return BuiltinToolResult.failure(
                "SHELL_NOT_AVAILABLE", "Shell not available (root, shizuku, user all failed)"
            )
        }
        if (!result.success) {
            return BuiltinToolResult.failure(
                result.errorCode ?: ScreenOperationError.SHELL_FAILED.code,
                "Shell tap at ($x, $y) failed: ${result.stderr}",
            )
        }
        return BuiltinToolResult.success("shell tap at ($x, $y)")
    }

    /**
     * Executes a long click at the given screen coordinates via shell command.
     */
    suspend fun executeShellLongClick(x: Int, y: Int): BuiltinToolResult {
        ensureService().getOrElse { e ->
            return BuiltinToolResult.failure(
                "SERVICE_UNAVAILABLE", e.message ?: "Service unavailable"
            )
        }
        val result = runShellCommand("input swipe $x $y $x $y 1500")
        if (!result.shellAvailable) {
            return BuiltinToolResult.failure(
                "SHELL_NOT_AVAILABLE", "Shell not available (root, shizuku, user all failed)"
            )
        }
        if (!result.success) {
            return BuiltinToolResult.failure(
                result.errorCode ?: ScreenOperationError.SHELL_FAILED.code,
                "Shell long click at ($x, $y) failed: ${result.stderr}",
            )
        }
        return BuiltinToolResult.success("shell long click at ($x, $y)")
    }

    /**
     * Executes a swipe gesture via shell command.
     */
    suspend fun executeShellSwipe(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        duration: Long,
    ): BuiltinToolResult {
        ensureService().getOrElse { e ->
            return BuiltinToolResult.failure(
                "SERVICE_UNAVAILABLE", e.message ?: "Service unavailable"
            )
        }
        val result = runShellCommand("input swipe $startX $startY $endX $endY $duration")
        if (!result.shellAvailable) {
            return BuiltinToolResult.failure(
                "SHELL_NOT_AVAILABLE", "Shell not available (root, shizuku, user all failed)"
            )
        }
        if (!result.success) {
            return BuiltinToolResult.failure(
                result.errorCode ?: ScreenOperationError.SHELL_FAILED.code,
                "Shell swipe from ($startX,$startY) to ($endX,$endY) failed: ${result.stderr}",
            )
        }
        return BuiltinToolResult.success("shell swipe from ($startX,$startY) to ($endX,$endY)")
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
        if (!result.shellAvailable) {
            return BuiltinToolResult.failure(
                "SHELL_NOT_AVAILABLE", "Shell not available (root, shizuku, user all failed)"
            )
        }
        if (!result.success) {
            return BuiltinToolResult.failure(
                result.errorCode ?: ScreenOperationError.SHELL_FAILED.code,
                "Shell keyevent $keyCode failed: ${result.stderr}",
            )
        }
        return BuiltinToolResult.success("shell keyevent $keyCode performed")
    }

    private suspend fun runShellCommand(command: String): ShellResult {
        val identity = ensureShellSession()
        val handle = shellSessionHandle
        if (identity == ShellIdentity.NONE || handle == null) {
            return ShellResult(-1, "", "No shell available (root, shizuku, and user all failed)", shellAvailable = false)
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
                    errorCode = ScreenOperationError.SHELL_TIMEOUT.code,
                )
            }
            is TerminalCommandOutcome.Failure -> {
                resetShellSession()
                ShellResult(-1, "", outcome.failure.message ?: "Shell command failed")
            }
            is TerminalCommandOutcome.SessionNotFound -> {
                resetShellSession()
                ShellResult(
                    -1, "", "Shell session lost",
                    errorCode = ScreenOperationError.SHELL_SESSION_LOST.code,
                )
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
