package com.niki914.zafiro.app.screen

import com.niki914.logging.Logger
import com.niki914.shizuku.display.VirtualDisplayController
import com.niki914.shizuku.display.VirtualDisplayPrepareResult
import com.niki914.zafiro.api.screen.CloseResult
import com.niki914.zafiro.api.screen.OpenKind
import com.niki914.zafiro.api.screen.OpenResult
import com.niki914.zafiro.api.screen.PhoneScreenId
import com.niki914.zafiro.api.screen.Screen
import com.niki914.zafiro.api.screen.ScreenAction
import com.niki914.zafiro.api.screen.ScreenId
import com.niki914.zafiro.api.screen.ScreenInfo
import com.niki914.zafiro.api.screen.Screens
import com.niki914.zafiro.api.screen.ShowResult
import com.niki914.zafiro.api.screen.ShowTarget
import com.niki914.zafiro.api.screen.VirtualDisplayContext
import com.niki914.zafiro.api.util.runCatchingCancellable
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.helper.ZafiroToast
import com.niki914.zafiro.business.permission.Permission
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
 * 前台会话绑定的虚拟屏只读状态（纯内存派生，不落盘）。
 */
internal data class ForegroundVirtualDisplayState(
    val screenId: ScreenId,
    val stage: StateFlow<VirtualDisplayStage>,
    val isOverlayShowing: Boolean,
)

/**
 * 屏幕目录。主屏一直在。虚拟屏按会话 1:1 并发隔离，创建默认静默驻留，首次创建时一次性申请所需权限。
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
    private val overlayVisibleConversations = ConcurrentHashMap.newKeySet<String>()
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
        val key = bindingKey(ownerConversationId) ?: return OpenResult.Rejected("NO_CONVERSATION")
        gate.withLock {
            val existingId = conversationScreens[key]
            val existing = existingId?.let { virtualScreens[it] }
            if (existing != null) {
                if (key == bindingKey(foregroundConversationId)) {
                    refreshForegroundState()
                }
                return OpenResult.Opened(existing.info())
            }
        }
        // 权限审批与无障碍连接等待可能挂起较久，移出互斥锁避免阻塞其他会话切换或关闭
        access.prepare(PhoneScreenAccess.Need.VirtualDisplay)?.let { denied ->
            return OpenResult.Rejected(denied.code)
        }
        return gate.withLock { openVirtualLocked(key) }
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
                when (val result = screen.show(ShowTarget.PackageName(normalized))) {
                    is ShowResult.Shown -> Unit
                    is ShowResult.Failed -> {
                        Logger.w(TAG, "launchApp failed screenId=${id.value} pkg=$normalized code=${result.code}")
                        val toastRes = if (result.code == "IN_USE_ON_MAIN") {
                            R.string.ui_virtual_display_app_in_use_on_main
                        } else {
                            R.string.ui_virtual_display_app_launch_failed
                        }
                        ZafiroToast.show(toastRes)
                    }
                    is ShowResult.Unsupported,
                    is ShowResult.Ambiguous -> {
                        Logger.w(TAG, "launchApp rejected screenId=${id.value} pkg=$normalized result=$result")
                        ZafiroToast.show(R.string.ui_virtual_display_app_launch_failed)
                    }
                }
            }.onFailure { error ->
                Logger.w(TAG, "launchApp failed screenId=${id.value} pkg=$normalized: ${error.message}")
                ZafiroToast.show(R.string.ui_virtual_display_app_launch_failed)
            }
        }
    }

    override fun hasVirtualScreen(conversationId: String?): Boolean {
        val key = bindingKey(conversationId) ?: return false
        val screenId = conversationScreens[key] ?: return false
        return virtualScreens.containsKey(screenId)
    }

    override fun virtualDisplayContext(conversationId: String?): VirtualDisplayContext? {
        val key = bindingKey(conversationId) ?: return null
        val screenId = conversationScreens[key] ?: return null
        return virtualScreens[screenId]?.currentContext()
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
        cardScope.launch {
            if (!permissionGranted(Permission.OVERLAY)) return@launch
            gate.withLock {
                if (bindingKey(foregroundConversationId) != key) return@withLock
                if (conversationScreens[key] != screenId) return@withLock
                val currentScreen = virtualScreens[screenId] ?: return@withLock
                overlayVisibleConversations.add(key)
                bindOverlay(currentScreen)
                // 选择应用没有预览画面，点开直接进大窗里的列表。
                if (currentScreen.stage.value is VirtualDisplayStage.AppPicker) {
                    VirtualDisplayCard.expandIfShowing()
                }
                refreshForegroundState()
            }
        }
        return true
    }

    override fun expandOverlay(conversationId: String?): Boolean {
        val key = bindingKey(conversationId) ?: return false
        val screenId = conversationScreens[key] ?: return false
        if (activeOverlayScreenId != screenId || !VirtualDisplayCard.isShowing) return false
        cardScope.launch {
            gate.withLock {
                if (activeOverlayScreenId == screenId && VirtualDisplayCard.isShowing) {
                    VirtualDisplayCard.expandIfShowing()
                }
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

                val shouldShowOverlay = key != null && targetScreen != null && overlayVisibleConversations.contains(key)
                virtualScreens.values.forEach { s ->
                    s.setPaused(!(shouldShowOverlay && s.id == targetScreenId))
                }

                if (shouldShowOverlay) {
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
                if (!VirtualDisplayCard.isShowing && overlayVisibleConversations.contains(key)) {
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
                bindingKey(screen.ownerConversationId)?.let { overlayVisibleConversations.remove(it) }
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

    private suspend fun openVirtualLocked(key: String): OpenResult {
        val existingId = conversationScreens[key]
        val existing = existingId?.let { virtualScreens[it] }
        if (existing != null) {
            if (key == bindingKey(foregroundConversationId)) {
                refreshForegroundState()
            }
            return OpenResult.Opened(existing.info())
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
            // 新建虚拟屏默认不弹悬浮窗，保持 0 FPS 休眠，等待用户从 Composer 顶槽或 /vd 主动打开
            screen.setPaused(true)
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
        if (virtualScreens.remove(screen.id) == null) return
        bindingKey(screen.ownerConversationId)?.let { key ->
            conversationScreens.remove(key, screen.id)
            overlayVisibleConversations.remove(key)
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
