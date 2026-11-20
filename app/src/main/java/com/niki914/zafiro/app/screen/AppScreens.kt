package com.niki914.zafiro.app.screen

import android.graphics.Bitmap
import com.niki914.logging.Logger
import com.niki914.shizuku.display.VirtualDisplayController
import com.niki914.shizuku.display.VirtualDisplayPrepareResult
import com.niki914.zafiro.accessibility.port.A11yDisplayScreen
import com.niki914.zafiro.accessibility.port.DefaultDisplayPort
import com.niki914.zafiro.accessibility.table.DisplayNodeTable
import com.niki914.zafiro.api.screen.ActResult
import com.niki914.zafiro.api.screen.ActionStatus
import com.niki914.zafiro.api.screen.CloseResult
import com.niki914.zafiro.api.screen.ImageFrame
import com.niki914.zafiro.api.screen.NodeInfo
import com.niki914.zafiro.api.screen.OpenKind
import com.niki914.zafiro.api.screen.OpenResult
import com.niki914.zafiro.api.screen.PhoneScreenId
import com.niki914.zafiro.api.screen.PointerAction
import com.niki914.zafiro.api.screen.Screen
import com.niki914.zafiro.api.screen.ScreenAction
import com.niki914.zafiro.api.screen.ScreenId
import com.niki914.zafiro.api.screen.ScreenImageUnavailable
import com.niki914.zafiro.api.screen.ScreenInfo
import com.niki914.zafiro.api.screen.ScreenKind
import com.niki914.zafiro.api.screen.ScreenSnapshot
import com.niki914.zafiro.api.screen.ScreenTreeUnavailable
import com.niki914.zafiro.api.screen.Screens
import com.niki914.zafiro.api.screen.ShowResult
import com.niki914.zafiro.api.screen.ShowTarget
import com.niki914.zafiro.api.screen.Target
import com.niki914.zafiro.api.util.runCatchingCancellable
import com.niki914.zafiro.app.ui.viewer.InAppPreviewBridge
import com.niki914.zafiro.business.permission.Permission
import com.niki914.zafiro.chat.agentic.device.AppLauncher
import com.niki914.zafiro.chat.agentic.device.UriOpener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * 针对物理主屏的屏幕实现。
 * 读、点与截屏交由 [A11yDisplayScreen]，动手前先过 [PhoneScreenAccess]。
 * 滚动和滑动在主屏上与指针一起走。
 * 应用启动与 URI 打开在此层委托 [AppLauncher] 和 [UriOpener]，不申请无障碍。
 */
class PhoneDisplayScreen(
    port: DefaultDisplayPort = DefaultDisplayPort(),
    nodes: DisplayNodeTable = DisplayNodeTable(),
    private val access: PhoneScreenAccess = PhoneScreenAccess(),
) : A11yDisplayScreen(PhoneScreenId, ScreenKind.Phone, port, nodes) {

    private val pointer: PhonePointerPlay = PhonePointer.play

    fun info(): ScreenInfo {
        val (width, height) = port.getDisplayMetrics()
        return ScreenInfo(id, kind, width, height)
    }

    override suspend fun snapshot(): ScreenSnapshot {
        access.prepare(PhoneScreenAccess.Need.Control)?.let { denied ->
            throw ScreenTreeUnavailable(denied.message)
        }
        return super.snapshot()
    }

    override suspend fun image(): ImageFrame {
        access.prepare(PhoneScreenAccess.Need.Capture)?.let { denied ->
            throw ScreenImageUnavailable(denied.code, denied.message)
        }
        return super.image()
    }

    override suspend fun act(action: ScreenAction): ActResult {
        access.prepare(PhoneScreenAccess.Need.Control)?.let { denied ->
            return ActResult.Failed(denied.code, null)
        }
        return when (val cue = phoneCue(action, ::locate)) {
            is PhoneCue.Reject -> ActResult.Failed(cue.code, null)
            PhoneCue.Skip -> super.act(action)
            is PhoneCue.Arrive -> pointer.afterArrival(cue.x.toFloat(), cue.y.toFloat()) {
                super.act(action)
            }
            is PhoneCue.Glide -> pointer.glide(
                cue.x1.toFloat(),
                cue.y1.toFloat(),
                cue.x2.toFloat(),
                cue.y2.toFloat(),
                cue.durationMs,
            ) {
                acted(
                    port.inject(
                        PointerAction.Swipe(cue.x1, cue.y1, cue.x2, cue.y2, cue.durationMs),
                    ),
                )
            }
        }
    }

    private fun locate(target: Target): Pair<Int, Int>? {
        return when (target) {
            is Target.Point -> target.x to target.y
            is Target.Node -> nodes.resolve(target.token)?.let(::centerOf)
        }
    }

    private suspend fun acted(status: ActionStatus): ActResult {
        return when (status) {
            ActionStatus.Done -> ActResult.Done(snapshot(), tabs = null)
            is ActionStatus.Failed -> ActResult.Failed(status.code, null)
        }
    }

    private fun centerOf(node: NodeInfo): Pair<Int, Int> {
        val bounds = node.bounds
        return (bounds.left + bounds.right) / 2 to (bounds.top + bounds.bottom) / 2
    }

    override suspend fun show(target: ShowTarget): ShowResult {
        access.prepare(PhoneScreenAccess.Need.OpenApp)?.let { denied ->
            return ShowResult.Failed(denied.code)
        }
        return when (target) {
            is ShowTarget.PackageName -> AppLauncher.launch(packageName = target.packageName, appName = null)
            is ShowTarget.Uri -> UriOpener.open(target.uri)
            is ShowTarget.InAppPreview -> showInAppPreview(target.url)
        }
    }

    private fun showInAppPreview(url: String): ShowResult {
        val trimmed = url.trim()
        if (trimmed.isBlank()) {
            return ShowResult.Failed("MISSING_REQUIRED_FIELD")
        }
        InAppPreviewBridge.open(trimmed)
        return ShowResult.Shown("Preview opened.")
    }
}

/**
 * 前台会话绑定的虚拟屏只读状态（纯内存派生，不落盘）。
 */
internal data class ForegroundVirtualDisplayState(
    val screenId: ScreenId,
    val stage: StateFlow<VirtualDisplayStage>,
    val backdropFrames: StateFlow<Bitmap?>,
    val isOverlayShowing: Boolean,
)

/**
 * 屏幕目录。主屏一直在。虚拟屏按会话 1:1 并发隔离，打开前向权限中心申请悬浮窗、无障碍和 Shizuku。
 */
object AppScreens : Screens {
    private const val TAG = "niki914_zafiro_AppScreens"

    private val access = PhoneScreenAccess()
    private val phone = PhoneDisplayScreen(access = access)
    private val gate = Mutex()
    private val cardScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val virtualScreens = ConcurrentHashMap<ScreenId, VirtualDisplayScreen>()
    private val conversationScreens = ConcurrentHashMap<String, ScreenId>()
    @Volatile
    private var foregroundConversationId: String? = null
    private val userDismissedConversations = ConcurrentHashMap.newKeySet<String>()
    @Volatile
    private var activeOverlayScreenId: ScreenId? = null

    private val _foregroundState = MutableStateFlow<ForegroundVirtualDisplayState?>(null)
    internal val foregroundState: StateFlow<ForegroundVirtualDisplayState?> = _foregroundState.asStateFlow()

    private val vdController: VirtualDisplayController by lazy {
        VirtualDisplayController.create(applicationContext())
    }

    private fun bindingKey(conversationId: String?): String? {
        return conversationId?.takeIf { it.isNotBlank() }
    }

    private fun refreshForegroundState() {
        val key = bindingKey(foregroundConversationId)
        val screenId = key?.let { conversationScreens[it] }
        val screen = screenId?.let { virtualScreens[it] }
        _foregroundState.value = screen?.let {
            ForegroundVirtualDisplayState(
                screenId = it.id,
                stage = it.stage,
                backdropFrames = it.backdropPreview(),
                isOverlayShowing = activeOverlayScreenId == it.id && VirtualDisplayCard.isShowing,
            )
        }
    }

    override fun list(callerConversationId: String?): List<ScreenInfo> {
        val extra = if (callerConversationId != null) {
            val targetScreenId = conversationScreens[callerConversationId]
            targetScreenId?.let { virtualScreens[it]?.info() }
        } else {
            null
        }
        return if (extra != null) {
            listOf(phone.info(), extra)
        } else if (callerConversationId == null) {
            listOf(phone.info()) + virtualScreens.values.map { it.info() }
        } else {
            listOf(phone.info())
        }
    }

    override fun get(id: ScreenId, callerConversationId: String?): Screen? {
        if (id == PhoneScreenId) return phone
        val screen = virtualScreens[id] ?: return null
        if (callerConversationId != null && screen.ownerConversationId != null && screen.ownerConversationId != callerConversationId) {
            return null
        }
        return screen
    }

    override suspend fun open(kind: OpenKind, ownerConversationId: String?): OpenResult {
        if (kind != OpenKind.VirtualDisplay) return OpenResult.Unsupported(kind)
        return gate.withLock { openVirtual(ownerConversationId) }
    }

    override suspend fun close(id: ScreenId, callerConversationId: String?): CloseResult {
        if (id == PhoneScreenId) return CloseResult.Rejected("PHONE_NOT_CLOSABLE")
        return gate.withLock {
            val screen = virtualScreens[id] ?: return@withLock CloseResult.Rejected("UNKNOWN_SCREEN")
            if (callerConversationId != null && screen.ownerConversationId != null && screen.ownerConversationId != callerConversationId) {
                return@withLock CloseResult.Rejected("PERMISSION_DENIED")
            }
            dismiss(screen)
            CloseResult.Closed
        }
    }

    override suspend fun closeForConversation(conversationId: String): CloseResult {
        val key = bindingKey(conversationId) ?: return CloseResult.Rejected("UNKNOWN_SCREEN")
        return gate.withLock {
            val screenId = conversationScreens[key] ?: return@withLock CloseResult.Rejected("UNKNOWN_SCREEN")
            val screen = virtualScreens[screenId] ?: return@withLock CloseResult.Rejected("UNKNOWN_SCREEN")
            dismiss(screen)
            CloseResult.Closed
        }
    }

    override fun launchApp(id: ScreenId, packageName: String) {
        val normalized = packageName.trim()
        if (normalized.isEmpty()) return
        val screen = virtualScreens[id] ?: return
        cardScope.launch {
            runCatchingCancellable {
                screen.show(ShowTarget.PackageName(normalized))
            }.onFailure { error ->
                Logger.w(TAG, "launchApp failed screenId=${id.value} pkg=$normalized: ${error.message}")
            }
        }
    }

    override fun hasVirtualScreen(conversationId: String?): Boolean {
        val key = bindingKey(conversationId) ?: return false
        val screenId = conversationScreens[key] ?: return false
        return virtualScreens.containsKey(screenId)
    }

    override fun isOverlayShowing(conversationId: String?): Boolean {
        val key = bindingKey(conversationId) ?: return false
        val screenId = conversationScreens[key] ?: return false
        return activeOverlayScreenId == screenId && VirtualDisplayCard.isShowing
    }

    override fun reopenOverlay(conversationId: String?): Boolean {
        val key = bindingKey(conversationId) ?: return false
        val screenId = conversationScreens[key] ?: return false
        if (!virtualScreens.containsKey(screenId)) return false
        userDismissedConversations.remove(key)
        cardScope.launch {
            gate.withLock {
                val currentScreen = virtualScreens[screenId] ?: return@withLock
                bindOverlay(currentScreen)
                refreshForegroundState()
            }
        }
        return true
    }

    /** 响应前台会话切换，将悬浮窗视口热重绑到当前会话的虚拟屏。 */
    override fun bindForegroundConversation(conversationId: String?) {
        cardScope.launch {
            gate.withLock {
                foregroundConversationId = conversationId
                val key = bindingKey(conversationId)
                val targetScreenId = key?.let { conversationScreens[it] }
                val targetScreen = targetScreenId?.let { virtualScreens[it] }

                val shouldShowOverlay = key != null && targetScreen != null && !userDismissedConversations.contains(key)
                virtualScreens.values.forEach { s ->
                    s.setPaused(!(shouldShowOverlay && s.id == targetScreenId))
                }

                if (shouldShowOverlay && targetScreen != null) {
                    bindOverlay(targetScreen)
                } else {
                    activeOverlayScreenId = null
                    VirtualDisplayCard.hide()
                }
                refreshForegroundState()
            }
        }
    }

    /** 当会话被销毁时彻底释放该会话持有的虚拟屏 */
    override fun onConversationDestroyed(conversationId: String) {
        val targetScreenId = conversationScreens[conversationId] ?: return
        val screen = virtualScreens[targetScreenId] ?: return
        cardScope.launch {
            gate.withLock {
                dismiss(screen)
            }
        }
    }

    /** 无障碍服务重新连上后，把已经开着的预览挂回去。 */
    fun restoreCard() {
        val key = bindingKey(foregroundConversationId) ?: return
        val targetScreenId = conversationScreens[key] ?: return
        val screen = virtualScreens[targetScreenId] ?: return
        if (VirtualDisplayCard.isShowing) return
        cardScope.launch {
            gate.withLock {
                if (!VirtualDisplayCard.isShowing && !userDismissedConversations.contains(key)) {
                    bindOverlay(screen)
                    refreshForegroundState()
                }
            }
        }
    }

    private suspend fun bindOverlay(screen: VirtualDisplayScreen) {
        activeOverlayScreenId = screen.id
        screen.setPaused(false)
        val binding = OverlayBinding(
            screenId = screen.id.value,
            frames = screen.preview(),
            stage = screen.stage,
            vdWidth = screen.width,
            vdHeight = screen.height,
            onClose = {
                bindingKey(screen.ownerConversationId)?.let { userDismissedConversations.add(it) }
                screen.setPaused(true)
                cardScope.launch {
                    gate.withLock {
                        if (activeOverlayScreenId == screen.id) {
                            activeOverlayScreenId = null
                            VirtualDisplayCard.hide()
                        }
                        refreshForegroundState()
                    }
                }
            },
            onNavKey = { keyCode ->
                cardScope.launch {
                    runCatchingCancellable {
                        screen.act(ScreenAction.Key(keyCode))
                    }.onFailure { error ->
                        Logger.e(TAG, "onNavKey failed keyCode=$keyCode", error)
                    }
                }
            },
            onEnterAppPicker = {
                screen.enterAppPicker()
            },
            onReturnFromAppPicker = {
                screen.returnFromAppPicker()
            },
            onQualityChanged = { highQuality ->
                screen.setHighQuality(highQuality)
            },
            onTouch = { event ->
                screen.injectTouch(event)
            },
            onLaunchApp = { packageName ->
                cardScope.launch {
                    runCatchingCancellable {
                        screen.show(ShowTarget.PackageName(packageName))
                    }.onFailure { error ->
                        Logger.e(TAG, "onLaunchApp failed packageName=$packageName", error)
                    }
                }
            },
        )
        VirtualDisplayCard.bind(binding)
    }

    private suspend fun openVirtual(ownerConversationId: String?): OpenResult {
        val key = bindingKey(ownerConversationId) ?: return OpenResult.Rejected("NO_CONVERSATION")
        val existingId = conversationScreens[key]
        if (existingId != null && virtualScreens.containsKey(existingId)) {
            val existing = virtualScreens.getValue(existingId)
            userDismissedConversations.remove(key)
            if (key == bindingKey(foregroundConversationId)) {
                bindOverlay(existing)
                refreshForegroundState()
            }
            return OpenResult.Opened(existing.info())
        }
        access.prepare(PhoneScreenAccess.Need.VirtualDisplay)?.let { denied ->
            return OpenResult.Rejected(denied.code)
        }
        if (!permissionGranted(Permission.OVERLAY)) {
            return OpenResult.Rejected("DISPLAY_FAILED")
        }
        when (vdController.prepare()) {
            VirtualDisplayPrepareResult.Ready -> Unit
            VirtualDisplayPrepareResult.Denied,
            VirtualDisplayPrepareResult.Unavailable,
            VirtualDisplayPrepareResult.BindFailed -> return OpenResult.Rejected("SHIZUKU_UNAVAILABLE")
        }
        return runCatchingCancellable {
            val (width, height, dpi) = virtualDisplaySize(applicationContext())
            val frames = VirtualDisplayFrames.open(vdController, width, height, dpi)
            if (frames == null) {
                if (virtualScreens.isEmpty()) vdController.close()
                return@runCatchingCancellable OpenResult.Rejected("DISPLAY_FAILED")
            }
            val screen = VirtualDisplayScreen(
                frames = frames,
                controller = vdController,
                access = access,
                ownerConversationId = key,
            )
            virtualScreens[screen.id] = screen
            conversationScreens[key] = screen.id
            userDismissedConversations.remove(key)

            val isForeground = key == bindingKey(foregroundConversationId)
            if (isForeground) {
                bindOverlay(screen)
            } else {
                screen.setPaused(true)
            }
            refreshForegroundState()

            OpenResult.Opened(screen.info())
        }.getOrElse { error ->
            Logger.e(TAG, "openVirtual failed unexpectedly", error)
            if (virtualScreens.isEmpty()) {
                runCatchingCancellable { vdController.close() }
            }
            OpenResult.Rejected("UNKNOWN_ERROR")
        }
    }

    private suspend fun dismiss(screen: VirtualDisplayScreen) {
        virtualScreens.remove(screen.id)
        bindingKey(screen.ownerConversationId)?.let { key ->
            conversationScreens.remove(key, screen.id)
            userDismissedConversations.remove(key)
        }
        if (activeOverlayScreenId == screen.id) {
            activeOverlayScreenId = null
            runCatchingCancellable { VirtualDisplayCard.hide() }
        }
        runCatchingCancellable { vdController.release(screen.port.displayId) }
        runCatchingCancellable { screen.closeLocal() }
        if (virtualScreens.isEmpty()) {
            runCatchingCancellable { vdController.close() }
        }
        refreshForegroundState()
    }
}
