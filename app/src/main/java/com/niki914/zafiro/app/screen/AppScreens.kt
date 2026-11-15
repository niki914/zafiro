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
import com.niki914.zafiro.business.permission.Permission
import com.niki914.zafiro.chat.agentic.device.AppLauncher
import com.niki914.zafiro.chat.agentic.device.UriOpener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
        return when (target) {
            is ShowTarget.AppName -> AppLauncher.launch(packageName = null, appName = target.name)
            is ShowTarget.PackageName -> AppLauncher.launch(packageName = target.packageName, appName = null)
            is ShowTarget.Uri -> UriOpener.open(target.uri)
        }
    }
}

/**
 * 屏幕目录。主屏一直在。虚拟屏最多一块，打开前向权限中心申请悬浮窗、无障碍和 Shizuku。
 */
object AppScreens : Screens {
    private const val TAG = "niki914_zafiro_AppScreens"

    private val phone = PhoneDisplayScreen()
    private val gate = Mutex()
    private val cardScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var virtual: VirtualDisplayScreen? = null
    private val vdController: VirtualDisplayController by lazy {
        VirtualDisplayController.create(applicationContext())
    }

    override fun list(): List<ScreenInfo> {
        val extra = virtual?.info()
        return if (extra == null) listOf(phone.info()) else listOf(phone.info(), extra)
    }

    override fun get(id: ScreenId): Screen? {
        if (id == PhoneScreenId) return phone
        val screen = virtual
        return if (screen?.id == id) screen else null
    }

    override suspend fun open(kind: OpenKind): OpenResult {
        if (kind != OpenKind.VirtualDisplay) return OpenResult.Unsupported(kind)
        return gate.withLock { openVirtual() }
    }

    override suspend fun close(id: ScreenId): CloseResult {
        if (id == PhoneScreenId) return CloseResult.Rejected("PHONE_NOT_CLOSABLE")
        return gate.withLock {
            val screen = virtual
            if (screen == null || screen.id != id) return@withLock CloseResult.Rejected("UNKNOWN_SCREEN")
            dismiss(screen)
            CloseResult.Closed
        }
    }

    /** 无障碍服务重新连上后，把已经开着的预览挂回去。 */
    fun restoreCard() {
        val screen = virtual ?: return
        if (VirtualDisplayCard.isShowing) return
        cardScope.launch {
            if (virtual == null || VirtualDisplayCard.isShowing) return@launch
            VirtualDisplayCard.show(
                frames = screen.preview(),
                status = screen.status,
                vdWidth = screen.width,
                vdHeight = screen.height,
                onClose = {
                    cardScope.launch {
                        gate.withLock {
                            val current = virtual ?: return@withLock
                            dismiss(current)
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
        }
    }

    private suspend fun openVirtual(): OpenResult {
        virtual?.let { return OpenResult.Opened(it.info()) }
        if (!permissionGranted(Permission.ACCESSIBILITY)) {
            return OpenResult.Rejected("ACCESSIBILITY_UNAVAILABLE")
        }
        if (!waitForDriverConnected { SystemAccessibilityDriver.isConnected }) {
            return OpenResult.Rejected("ACCESSIBILITY_UNAVAILABLE")
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
                vdController.close()
                return@runCatchingCancellable OpenResult.Rejected("DISPLAY_FAILED")
            }
            val screen = VirtualDisplayScreen(frames, vdController)
            val shown = VirtualDisplayCard.show(
                frames = screen.preview(),
                status = screen.status,
                vdWidth = screen.width,
                vdHeight = screen.height,
                onClose = {
                    cardScope.launch {
                        gate.withLock {
                            val current = virtual ?: return@withLock
                            dismiss(current)
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
            if (!shown) {
                vdController.release(frames.displayId)
                screen.closeLocal()
                vdController.close()
                return@runCatchingCancellable OpenResult.Rejected("DISPLAY_FAILED")
            }
            virtual = screen
            OpenResult.Opened(screen.info())
        }.getOrElse { error ->
            Logger.e(TAG, "openVirtual failed unexpectedly", error)
            runCatchingCancellable { vdController.close() }
            OpenResult.Rejected("UNKNOWN_ERROR")
        }
    }

    private suspend fun dismiss(screen: VirtualDisplayScreen) {
        runCatchingCancellable { VirtualDisplayCard.hide() }
        runCatchingCancellable { vdController.release(screen.port.displayId) }
        runCatchingCancellable { screen.closeLocal() }
        virtual = null
        runCatchingCancellable { vdController.close() }
    }
}
