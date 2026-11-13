package com.niki914.zafiro.chat.agentic.screen

import com.niki914.xposed.api.util.ContextProvider
import com.niki914.zafiro.api.screen.ActResult
import com.niki914.zafiro.api.screen.CloseResult
import com.niki914.zafiro.api.screen.ImageFrame
import com.niki914.zafiro.api.screen.OpenKind
import com.niki914.zafiro.api.screen.OpenResult
import com.niki914.zafiro.api.screen.PhoneScreenId
import com.niki914.zafiro.api.screen.Screen
import com.niki914.zafiro.api.screen.ScreenAction
import com.niki914.zafiro.api.screen.ScreenId
import com.niki914.zafiro.api.screen.ScreenInfo
import com.niki914.zafiro.api.screen.ScreenKind
import com.niki914.zafiro.api.screen.ScreenSnapshot
import com.niki914.zafiro.api.screen.Screens
import com.niki914.zafiro.api.screen.SemanticToken
import com.niki914.zafiro.api.screen.ShowResult
import com.niki914.zafiro.api.screen.ShowTarget
import com.niki914.zafiro.api.screen.Target
import com.niki914.zafiro.chat.agentic.ScreenCaptureIngestor
import com.niki914.zafiro.chat.agentic.ScreenCaptureIngestor.Stage
import com.niki914.zafiro.chat.agentic.ScreenCaptureIngestor.ToolCaptureResult
import com.niki914.zafiro.chat.agentic.accessibility.AccessibilityController
import com.niki914.zafiro.chat.agentic.accessibility.NodeAction
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolResult
import com.niki914.zafiro.chat.agentic.buildin.impl.LastPhoneCapture
import com.niki914.zafiro.chat.agentic.buildin.impl.LaunchAppBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.OpenUriBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.ScreenImageUnavailable
import com.niki914.zafiro.chat.agentic.toImageToolResult
import com.niki914.zafiro.chat.agentic.toToolError
import kotlinx.coroutines.CancellationException

// Phase 1 glue: connects to AccessibilityController directly; to be replaced in Phase 2 by DefaultDisplayPort.
/**
 * 只有手机这一块屏。看和点进现有无障碍/shell，open 进现有启动和 ACTION_VIEW，
 * 截屏进现有 capture-and-ingest。虚拟屏和浏览器先拒绝。
 */
object PhoneScreens : Screens {
    private val phone = PhoneScreen()

    override fun list(): List<ScreenInfo> = listOf(phone.info())

    override fun get(id: ScreenId): Screen? = if (id == PhoneScreenId) phone else null

    override suspend fun open(kind: OpenKind): OpenResult = OpenResult.Unsupported(kind)

    override suspend fun close(id: ScreenId): CloseResult {
        if (id == PhoneScreenId) return CloseResult.Rejected("PHONE_NOT_CLOSABLE")
        return CloseResult.Rejected("UNKNOWN_SCREEN")
    }
}

private class PhoneScreen : Screen {
    override val id: ScreenId = PhoneScreenId
    override val kind: ScreenKind = ScreenKind.Phone

    fun info(): ScreenInfo {
        val (width, height) = size()
        return ScreenInfo(id, kind, width, height)
    }

    override suspend fun snapshot(): ScreenSnapshot {
        val captured = try {
            AccessibilityController.captureScreen()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw ScreenTreeUnavailable(error.message ?: "capture failed")
        }
        val snap = captured.getOrElse { error ->
            throw ScreenTreeUnavailable(error.message ?: "capture failed")
        }
        val (width, height) = size()
        val (cachedWidth, cachedHeight) = AccessibilityController.screenSize()
        return ScreenSnapshot(
            screen = id,
            version = snap.version,
            width = if (cachedWidth > 0) cachedWidth else width,
            height = if (cachedHeight > 0) cachedHeight else height,
            url = null,
            nodes = AccessibilityController.exportedNodes(),
            yaml = snap.yaml,
        )
    }

    override suspend fun image(): ImageFrame {
        if (ContextProvider.awaitIfAvailable() == null) {
            throw ScreenImageUnavailable("CODEC_UNAVAILABLE", "Image codec is not available.")
        }
        LastPhoneCapture.clear()
        val captured = try {
            ScreenCaptureIngestor.captureForTool()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw ScreenImageUnavailable("SCREENSHOT_FAILED", error.message ?: "Screen capture failed.")
        }
        return when (captured) {
            is ToolCaptureResult.Ok -> {
                LastPhoneCapture.offer(
                    captured.ingest.toImageToolResult(
                        message = "Screenshot captured: ${captured.ingest.image.path}",
                    ),
                )
                ImageFrame(
                    id,
                    captured.ingest.image.width,
                    captured.ingest.image.height,
                    byteArrayOf(1),
                )
            }
            is ToolCaptureResult.Err -> throw captured.asUnavailable()
            is ToolCaptureResult.IngestErr -> {
                val (code, message) = captured.error.toToolError()
                throw ScreenImageUnavailable(code, message)
            }
        }
    }

    override suspend fun act(action: ScreenAction): ActResult {
        return when (action) {
            is ScreenAction.Tap -> onTarget(action.target, NodeAction.CLICK) { x, y ->
                AccessibilityController.executeShellTap(x, y)
            }
            is ScreenAction.LongClick -> onTarget(action.target, NodeAction.LONG_CLICK) { x, y ->
                AccessibilityController.executeShellLongClick(x, y)
            }
            is ScreenAction.Scroll -> when (val target = action.target) {
                is Target.Node -> call {
                    AccessibilityController.executeNodeAction(
                        target.token.text(),
                        if (action.forward) NodeAction.SCROLL_FORWARD else NodeAction.SCROLL_BACKWARD,
                        null,
                    )
                }
                is Target.Point -> {
                    val dy = if (action.forward) -200 else 200
                    call {
                        AccessibilityController.executeShellSwipe(
                            target.x,
                            target.y,
                            target.x,
                            target.y + dy,
                            300,
                        )
                    }
                }
            }
            is ScreenAction.SetText -> when (val target = action.target) {
                is Target.Node -> call {
                    AccessibilityController.executeNodeAction(
                        target.token.text(),
                        NodeAction.SET_TEXT,
                        action.text,
                    )
                }
                is Target.Point -> ActResult.Failed(
                    "INVALID_ARGUMENTS",
                    blank(0, 0, "set_text requires a token."),
                )
            }
            is ScreenAction.Swipe -> call {
                AccessibilityController.executeShellSwipe(
                    action.x1,
                    action.y1,
                    action.x2,
                    action.y2,
                    action.durationMs,
                )
            }
            is ScreenAction.Key -> call { AccessibilityController.executeKeyEvent(action.code) }
            is ScreenAction.History -> ActResult.Unsupported(
                if (action.forward) "history_forward" else "history_back",
            )
            ScreenAction.ListTabs -> ActResult.Unsupported("tabs")
            is ScreenAction.NewTab -> ActResult.Unsupported("new_tab")
            is ScreenAction.CloseTab -> ActResult.Unsupported("close_tab")
            is ScreenAction.SwitchTab -> ActResult.Unsupported("switch_tab")
        }
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

    private suspend fun onTarget(
        target: Target,
        nodeAction: NodeAction,
        point: suspend (Int, Int) -> BuiltinToolResult,
    ): ActResult {
        return when (target) {
            is Target.Node -> call {
                AccessibilityController.executeNodeAction(target.token.text(), nodeAction, null)
            }
            is Target.Point -> call { point(target.x, target.y) }
        }
    }

    private suspend fun call(block: suspend () -> BuiltinToolResult): ActResult {
        val result = try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return ActResult.Failed(error.javaClass.simpleName, blank(0, 0, error.message ?: "failed"))
        }
        if (result.ok) return ActResult.Done(snapshot(), tabs = null)
        return ActResult.Failed(result.code, blank(0, 0, result.message))
    }

    private fun size(): Pair<Int, Int> {
        val (cachedWidth, cachedHeight) = AccessibilityController.screenSize()
        if (cachedWidth > 0 && cachedHeight > 0) return cachedWidth to cachedHeight
        val context = ContextProvider.awaitIfAvailable() ?: return 0 to 0
        return try {
            val metrics = context.resources.displayMetrics
            metrics.widthPixels to metrics.heightPixels
        } catch (_: Throwable) {
            0 to 0
        }
    }

    private fun blank(width: Int, height: Int, yaml: String): ScreenSnapshot {
        return ScreenSnapshot(
            screen = id,
            version = AccessibilityController.currentVersion,
            width = width,
            height = height,
            url = null,
            nodes = emptyList(),
            yaml = yaml,
        )
    }
}

private fun SemanticToken.text(): String = "${version}_$index"

/** 无障碍抓屏失败。调用方把它交成工具失败，不能当成一棵界面树。 */
internal class ScreenTreeUnavailable(message: String) : RuntimeException(message)

private fun ToolCaptureResult.Err.asUnavailable(): ScreenImageUnavailable {
    val code = when (stage) {
        Stage.CODEC_UNAVAILABLE -> "CODEC_UNAVAILABLE"
        Stage.ACCESSIBILITY_UNAVAILABLE -> "ACCESSIBILITY_UNAVAILABLE"
        Stage.CAPTURE_FAILED, Stage.ENCODE_FAILED, Stage.INGEST_FAILED -> "SCREENSHOT_FAILED"
    }
    return ScreenImageUnavailable(code, message ?: code)
}
