package com.niki914.zafiro.app.screen

import com.niki914.logging.Logger
import com.niki914.shizuku.display.VirtualDisplayController
import com.niki914.shizuku.display.VirtualDisplayPrepareResult
import com.niki914.zafiro.accessibility.driver.SystemAccessibilityDriver
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
 * 屏幕目录。主屏一直在。虚拟屏最多一块，打开前向权限中心申请悬浮窗、无障碍和 Shizuku。
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

    private val vdController: VirtualDisplayController by lazy {
        VirtualDisplayController.create(applicationContext())
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

    /** 响应前台会话切换，将悬浮窗视口热重绑到当前会话的虚拟屏。 */
    override fun bindForegroundConversation(conversationId: String?) {
        cardScope.launch {
            gate.withLock {
                foregroundConversationId = conversationId
                if (conversationId == null) {
                    virtualScreens.values.forEach { it.setPaused(true) }
                    activeOverlayScreenId = null
                    VirtualDisplayCard.hide()
                    return@withLock
                }
                val targetScreenId = conversationScreens[conversationId]
                val targetScreen = targetScreenId?.let { virtualScreens[it] }

                // 暂停所有后台屏以防耗电，恢复目标前台屏
                virtualScreens.values.forEach { s ->
                    s.setPaused(s.id != targetScreenId)
                }

                if (targetScreen != null && !userDismissedConversations.contains(conversationId)) {
                    bindOverlay(targetScreen)
                } else {
                    activeOverlayScreenId = null
                    VirtualDisplayCard.hide()
                }
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
        val convId = foregroundConversationId ?: return
        val targetScreenId = conversationScreens[convId] ?: return
        val screen = virtualScreens[targetScreenId] ?: return
        if (VirtualDisplayCard.isShowing) return
        cardScope.launch {
            gate.withLock {
                if (!VirtualDisplayCard.isShowing && !userDismissedConversations.contains(convId)) {
                    bindOverlay(screen)
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
            status = screen.status,
            vdWidth = screen.width,
            vdHeight = screen.height,
            onClose = {
                // 点击叉叉：仅隐藏悬浮窗视口并标记当前会话已收起，绝不调用 dismiss() 杀死虚拟屏
                screen.ownerConversationId?.let { userDismissedConversations.add(it) }
                cardScope.launch {
                    gate.withLock {
                        if (activeOverlayScreenId == screen.id) {
                            activeOverlayScreenId = null
                            VirtualDisplayCard.hide()
                        }
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
            onQualityChanged = { highQuality ->
                screen.setHighQuality(highQuality)
            },
            onTouch = { event ->
                screen.injectTouch(event)
            },
        )
        VirtualDisplayCard.bind(binding)
    }

    private suspend fun openVirtual(ownerConversationId: String?): OpenResult {
        if (ownerConversationId != null) {
            val existingId = conversationScreens[ownerConversationId]
            if (existingId != null && virtualScreens.containsKey(existingId)) {
                return OpenResult.Opened(virtualScreens.getValue(existingId).info())
            }
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
                ownerConversationId = ownerConversationId,
            )
            virtualScreens[screen.id] = screen
            if (ownerConversationId != null) {
                conversationScreens[ownerConversationId] = screen.id
            }

            // 若当前正好处于发起会话且用户未主动叉掉，绑定悬浮窗
            val isForeground = ownerConversationId == null || ownerConversationId == foregroundConversationId
            if (isForeground && (ownerConversationId == null || !userDismissedConversations.contains(ownerConversationId))) {
                bindOverlay(screen)
            } else {
                screen.setPaused(true)
            }

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
        screen.ownerConversationId?.let {
            conversationScreens.remove(it)
            userDismissedConversations.remove(it)
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
    }
}
