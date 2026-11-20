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
import com.niki914.zafiro.business.application.ApplicationService
import com.niki914.zafiro.business.permission.Permission
import com.niki914.zafiro.business.permission.PermissionManager
import com.niki914.zafiro.business.permission.PermissionState
import com.niki914.zafiro.service.requireService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 虚拟屏运行状态（待命、启动中）。
 */
internal enum class VirtualDisplayStatus {
    Idle,
    Launching,
}

/**
 * 一块虚拟屏。读、点、截图走无障碍端口，自己持有版本表。不画主屏指针。
 */
internal class VirtualDisplayScreen(
    private val frames: VirtualDisplayFrames,
    private val controller: VirtualDisplayController,
    private val access: PhoneScreenAccess = PhoneScreenAccess(),
    val ownerConversationId: String? = null,
) : A11yDisplayScreen(
    ScreenId("vd-${frames.displayId}"),
    ScreenKind.VirtualDisplay,
    A11yDisplayPort(frames.displayId),
    DisplayNodeTable(),
) {
    private val _status = MutableStateFlow(VirtualDisplayStatus.Idle)
    val status: StateFlow<VirtualDisplayStatus> = _status.asStateFlow()

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
        _status.value = VirtualDisplayStatus.Launching
        val result = when (target) {
            is ShowTarget.PackageName -> launch(target.packageName)
            is ShowTarget.Uri -> ShowResult.Unsupported("uri")
            is ShowTarget.InAppPreview -> ShowResult.Unsupported("in_app_preview")
        }
        if (result !is ShowResult.Shown) {
            _status.value = VirtualDisplayStatus.Idle
        }
        return result
    }

    private suspend fun launch(packageName: String): ShowResult {
        return when (controller.startApp(port.displayId, packageName)) {
            StartAppResult.Ok -> ShowResult.Shown(packageName)
            StartAppResult.InUseOnMain -> ShowResult.Failed("IN_USE_ON_MAIN")
            StartAppResult.NotFound -> ShowResult.Failed("APP_NOT_FOUND")
            StartAppResult.Failed -> ShowResult.Failed("START_FAILED")
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
