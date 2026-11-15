package com.niki914.zafiro.app.screen

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
import com.niki914.zafiro.chat.agentic.buildin.impl.LaunchAppBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.OpenUriBuiltin

/**
 * 针对物理主屏的屏幕实现。
 * 读、点与截屏交由 [A11yDisplayScreen]，动手前先过 [PhoneScreenAccess]。
 * 滚动和滑动在主屏上与指针一起走。
 * 应用启动与 URI 打开在此层桥接 [LaunchAppBuiltin] 和 [OpenUriBuiltin]，不申请无障碍。
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
            is ShowTarget.AppName -> LaunchAppBuiltin().showOnPhone(null, target.name)
            is ShowTarget.PackageName -> LaunchAppBuiltin().showOnPhone(target.packageName, null)
            is ShowTarget.Uri -> {
                val opened = OpenUriBuiltin().openForScreen(target.uri)
                if (opened.ok) {
                    ShowResult.Shown(opened.message)
                } else {
                    ShowResult.Failed(opened.code)
                }
            }
        }
    }
}

/**
 * 屏幕目录服务。
 * 本轮仅装配物理主屏，不做虚拟副屏与浏览器屏。
 */
object AppScreens : Screens {
    private val phone = PhoneDisplayScreen()

    override fun list(): List<ScreenInfo> = listOf(phone.info())

    override fun get(id: ScreenId): Screen? = if (id == PhoneScreenId) phone else null

    override suspend fun open(kind: OpenKind): OpenResult = OpenResult.Unsupported(kind)

    override suspend fun close(id: ScreenId): CloseResult {
        if (id == PhoneScreenId) return CloseResult.Rejected("PHONE_NOT_CLOSABLE")
        return CloseResult.Rejected("UNKNOWN_SCREEN")
    }
}
