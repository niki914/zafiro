package com.niki914.zafiro.accessibility.port

import com.niki914.zafiro.accessibility.table.DisplayNodeTable
import com.niki914.zafiro.api.screen.ActResult
import com.niki914.zafiro.api.screen.ActionStatus
import com.niki914.zafiro.api.screen.DisplayScreen
import com.niki914.zafiro.api.screen.ImageFrame
import com.niki914.zafiro.api.screen.NodeAction
import com.niki914.zafiro.api.screen.PointerAction
import com.niki914.zafiro.api.screen.ScreenAction
import com.niki914.zafiro.api.screen.ScreenId
import com.niki914.zafiro.api.screen.ScreenKind
import com.niki914.zafiro.api.screen.ScreenSnapshot
import com.niki914.zafiro.api.screen.ScreenTreeUnavailable
import com.niki914.zafiro.api.screen.ShowResult
import com.niki914.zafiro.api.screen.ShowStatus
import com.niki914.zafiro.api.screen.ShowTarget
import com.niki914.zafiro.api.screen.Target

/**
 * 针对标准 [A11yDisplayPort] 与专属 [DisplayNodeTable] 的屏幕门面实现。
 *
 * 每一块屏幕（无论是主屏还是虚拟副屏）拥有独立的 [DisplayNodeTable] 实例，
 * 版本号和节点缓存完全相互隔离。
 */
open class A11yDisplayScreen(
    override val id: ScreenId,
    override val kind: ScreenKind,
    override val port: A11yDisplayPort,
    override val nodes: DisplayNodeTable = DisplayNodeTable(),
) : DisplayScreen {

    override suspend fun snapshot(): ScreenSnapshot {
        val version = nodes.mintVersion()
        val flatNodes = port.readNodes(version)
        if (flatNodes.isEmpty()) {
            throw ScreenTreeUnavailable("No accessibility node tree available on screen '${id.value}'.")
        }
        nodes.remember(version, flatNodes)
        val yaml = port.latestYaml()
        val (width, height) = port.getDisplayMetrics()

        return ScreenSnapshot(
            screen = id,
            version = version,
            width = width,
            height = height,
            url = null,
            nodes = flatNodes,
            yaml = yaml,
        )
    }

    override suspend fun image(): ImageFrame = port.frame()

    override suspend fun act(action: ScreenAction): ActResult {
        return when (action) {
            is ScreenAction.Tap -> actOnTarget(action.target, NodeAction.Click) { x, y ->
                port.inject(PointerAction.Tap(x, y))
            }
            is ScreenAction.LongClick -> actOnTarget(action.target, NodeAction.LongClick) { x, y ->
                port.inject(PointerAction.LongClick(x, y))
            }
            is ScreenAction.Scroll -> when (val target = action.target) {
                is Target.Node -> {
                    val node = nodes.resolve(target.token)
                        ?: return ActResult.Failed("NODE_STALE", null)
                    val status = port.perform(
                        target.token,
                        if (action.forward) NodeAction.ScrollForward else NodeAction.ScrollBackward,
                        null,
                    )
                    statusToActResult(status)
                }
                is Target.Point -> {
                    val dy = if (action.forward) -200 else 200
                    statusToActResult(
                        port.inject(PointerAction.Swipe(target.x, target.y, target.x, target.y + dy, 300L)),
                    )
                }
            }
            is ScreenAction.SetText -> when (val target = action.target) {
                is Target.Node -> {
                    val node = nodes.resolve(target.token)
                        ?: return ActResult.Failed("NODE_STALE", null)
                    val status = port.perform(target.token, NodeAction.SetText, action.text)
                    statusToActResult(status)
                }
                is Target.Point -> ActResult.Failed("INVALID_ARGUMENTS", null)
            }
            is ScreenAction.Swipe -> statusToActResult(
                port.inject(PointerAction.Swipe(action.x1, action.y1, action.x2, action.y2, action.durationMs)),
            )
            is ScreenAction.Key -> statusToActResult(port.inject(PointerAction.Key(action.code)))
            is ScreenAction.History -> ActResult.Unsupported(if (action.forward) "history_forward" else "history_back")
            ScreenAction.ListTabs -> ActResult.Unsupported("tabs")
            is ScreenAction.NewTab -> ActResult.Unsupported("new_tab")
            is ScreenAction.CloseTab -> ActResult.Unsupported("close_tab")
            is ScreenAction.SwitchTab -> ActResult.Unsupported("switch_tab")
        }
    }

    override suspend fun show(target: ShowTarget): ShowResult {
        return when (val status = port.show(target)) {
            is ShowStatus.Done -> ShowResult.Shown()
            is ShowStatus.Ambiguous -> ShowResult.Ambiguous(status.name, status.candidates)
            is ShowStatus.Failed -> ShowResult.Failed(status.code)
        }
    }

    private suspend fun actOnTarget(
        target: Target,
        nodeAction: NodeAction,
        onPoint: suspend (Int, Int) -> ActionStatus,
    ): ActResult {
        return when (target) {
            is Target.Node -> {
                val node = nodes.resolve(target.token)
                    ?: return ActResult.Failed("NODE_STALE", null)
                val status = port.perform(target.token, nodeAction, null)
                statusToActResult(status)
            }
            is Target.Point -> statusToActResult(onPoint(target.x, target.y))
        }
    }

    private suspend fun statusToActResult(status: ActionStatus): ActResult {
        return when (status) {
            is ActionStatus.Done -> ActResult.Done(snapshot(), tabs = null)
            is ActionStatus.Failed -> ActResult.Failed(status.code, null)
        }
    }
}
