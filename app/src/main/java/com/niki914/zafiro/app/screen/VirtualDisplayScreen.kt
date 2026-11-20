package com.niki914.zafiro.app.screen

import android.content.Context
import android.os.Build
import android.view.MotionEvent
import android.view.WindowManager
import com.niki914.shizuku.display.StartAppResult
import com.niki914.shizuku.display.VirtualDisplayController
import com.niki914.zafiro.accessibility.port.A11yDisplayPort
import com.niki914.zafiro.accessibility.port.A11yDisplayScreen
import com.niki914.zafiro.accessibility.table.DisplayNodeTable
import com.niki914.zafiro.api.screen.ActResult
import com.niki914.zafiro.api.screen.ImageFrame
import com.niki914.zafiro.api.screen.ScreenAction
import com.niki914.zafiro.api.screen.ScreenId
import com.niki914.zafiro.api.screen.ScreenImageUnavailable
import com.niki914.zafiro.api.screen.ScreenInfo
import com.niki914.zafiro.api.screen.ScreenKind
import com.niki914.zafiro.api.screen.ScreenSnapshot
import com.niki914.zafiro.api.screen.ScreenTreeUnavailable
import com.niki914.zafiro.api.screen.ShowResult
import com.niki914.zafiro.api.screen.ShowTarget
import com.niki914.zafiro.api.util.runCatchingCancellable
import com.niki914.zafiro.business.application.ApplicationService
import com.niki914.zafiro.business.permission.Permission
import com.niki914.zafiro.business.permission.PermissionManager
import com.niki914.zafiro.business.permission.PermissionState
import com.niki914.zafiro.chat.agentic.device.AppInfoProvider
import com.niki914.zafiro.service.requireService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 虚拟屏唯一视图阶段：大窗、小窗与主对话页卡片共同订阅，避免状态位分散撕裂。
 */
internal sealed interface VirtualDisplayStage {
    data class AppPicker(val backgroundApp: Running? = null) : VirtualDisplayStage
    data class Launching(val packageName: String, val appName: String) : VirtualDisplayStage
    data class Running(val packageName: String, val appName: String) : VirtualDisplayStage
}

/**
 * 一块虚拟屏。读、点、截图走无障碍端口，自己持有版本表。不画主屏指针。
 */
internal class VirtualDisplayScreen(
    private val frames: VirtualDisplayFrames,
    private val controller: VirtualDisplayController,
    private val access: PhoneScreenAccess = PhoneScreenAccess(),
    @Volatile
    var ownerConversationId: String? = null,
) : A11yDisplayScreen(
    ScreenId("vd-${frames.displayId}"),
    ScreenKind.VirtualDisplay,
    A11yDisplayPort(frames.displayId),
    DisplayNodeTable(),
) {
    private val _stage = MutableStateFlow<VirtualDisplayStage>(VirtualDisplayStage.AppPicker())
    val stage: StateFlow<VirtualDisplayStage> = _stage.asStateFlow()

    fun info(): ScreenInfo = ScreenInfo(id, kind, frames.width, frames.height)

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
        return super.act(action)
    }

    override suspend fun show(target: ShowTarget): ShowResult {
        access.prepare(PhoneScreenAccess.Need.OpenApp)?.let { denied ->
            return ShowResult.Failed(denied.code)
        }
        return when (target) {
            is ShowTarget.PackageName -> launch(target.packageName)
            is ShowTarget.Uri -> ShowResult.Unsupported("uri")
            is ShowTarget.InAppPreview -> ShowResult.Unsupported("in_app_preview")
        }
    }

    fun enterAppPicker() {
        val current = _stage.value
        val background = when (current) {
            is VirtualDisplayStage.Running -> current
            is VirtualDisplayStage.AppPicker -> current.backgroundApp
            is VirtualDisplayStage.Launching -> null
        }
        _stage.value = VirtualDisplayStage.AppPicker(backgroundApp = background)
    }

    fun returnFromAppPicker(): Boolean {
        val current = _stage.value as? VirtualDisplayStage.AppPicker ?: return false
        val target = current.backgroundApp ?: return false
        _stage.value = target
        return true
    }

    private suspend fun launch(packageName: String): ShowResult {
        val previous = _stage.value
        val resolvedName = runCatchingCancellable {
            AppInfoProvider.cache().findByPackageName(packageName)?.appName
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: packageName
        _stage.value = VirtualDisplayStage.Launching(
            packageName = packageName,
            appName = resolvedName,
        )
        return when (controller.startApp(port.displayId, packageName)) {
            StartAppResult.Ok -> {
                _stage.value = VirtualDisplayStage.Running(
                    packageName = packageName,
                    appName = resolvedName,
                )
                RecentVirtualDisplayAppsStore.record(packageName)
                ShowResult.Shown(packageName)
            }
            StartAppResult.InUseOnMain -> {
                restoreStageAfterLaunchFailure(previous)
                ShowResult.Failed("IN_USE_ON_MAIN")
            }
            StartAppResult.NotFound -> {
                restoreStageAfterLaunchFailure(previous)
                ShowResult.Failed("APP_NOT_FOUND")
            }
            StartAppResult.Failed -> {
                restoreStageAfterLaunchFailure(previous)
                ShowResult.Failed("START_FAILED")
            }
        }
    }

    private fun restoreStageAfterLaunchFailure(previous: VirtualDisplayStage) {
        _stage.value = when (previous) {
            is VirtualDisplayStage.Launching -> VirtualDisplayStage.AppPicker()
            else -> previous
        }
    }

    fun preview() = frames.frames

    fun setHighQuality(enabled: Boolean) = frames.setHighQuality(enabled)

    fun setPaused(isPaused: Boolean) = frames.setPaused(isPaused)

    val width: Int get() = frames.width
    val height: Int get() = frames.height

    fun injectTouch(event: MotionEvent) = controller.injectTouch(frames.displayId, event)

    fun closeLocal() = frames.release()
}

internal fun virtualDisplaySize(context: Context): Triple<Int, Int, Int> {
    val manager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    val (width, height) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && manager != null) {
        val bounds = manager.maximumWindowMetrics.bounds
        bounds.width() to bounds.height()
    } else {
        val metrics = context.resources.displayMetrics
        metrics.widthPixels to metrics.heightPixels
    }
    return Triple(width, height, context.resources.configuration.densityDpi)
}

internal suspend fun permissionGranted(permission: Permission): Boolean {
    val manager = requireService<PermissionManager>()
    if (manager.status(permission) == PermissionState.GRANTED) return true
    return manager.request(permission).finalState == PermissionState.GRANTED
}

internal fun applicationContext(): Context = requireService<ApplicationService>().getApplication()
