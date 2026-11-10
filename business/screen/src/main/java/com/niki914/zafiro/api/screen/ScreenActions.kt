package com.niki914.zafiro.api.screen

sealed interface Target {
    data class Node(val token: SemanticToken) : Target
    data class Point(val x: Int, val y: Int) : Target
}

sealed interface ScreenAction {
    data class Tap(val target: Target) : ScreenAction
    data class LongClick(val target: Target) : ScreenAction
    data class Scroll(val target: Target, val forward: Boolean) : ScreenAction
    data class SetText(val target: Target, val text: String) : ScreenAction
    data class Swipe(
        val x1: Int,
        val y1: Int,
        val x2: Int,
        val y2: Int,
        val durationMs: Long,
    ) : ScreenAction

    data class Key(val code: Int) : ScreenAction
    data class History(val forward: Boolean) : ScreenAction
    data object ListTabs : ScreenAction
    data class NewTab(val url: String?) : ScreenAction
    data class CloseTab(val tabId: String) : ScreenAction
    data class SwitchTab(val tabId: String) : ScreenAction
}

data class TabInfo(
    val tabId: String,
    val url: String,
    val title: String,
    val active: Boolean,
)

sealed interface ActResult {
    data class Done(val snapshot: ScreenSnapshot, val tabs: List<TabInfo>?) : ActResult
    data class Unsupported(val action: String) : ActResult
    data class Failed(val code: String, val snapshot: ScreenSnapshot?) : ActResult
}

sealed interface ShowTarget {
    data class AppName(val name: String) : ShowTarget
    data class PackageName(val packageName: String) : ShowTarget
    data class Uri(val uri: String) : ShowTarget
}

data class AppHit(val packageName: String, val label: String)

sealed interface ShowResult {
    data class Shown(val snapshot: ScreenSnapshot) : ShowResult
    data class Ambiguous(val name: String, val candidates: List<AppHit>) : ShowResult
    data class Unsupported(val target: String) : ShowResult
    data class Failed(val code: String) : ShowResult
}

enum class NodeAction { Click, LongClick, SetText, ScrollForward, ScrollBackward }

sealed interface ActionStatus {
    data object Done : ActionStatus
    data class Failed(val code: String) : ActionStatus
}

sealed interface ShowStatus {
    data object Done : ShowStatus
    data class Ambiguous(val name: String, val candidates: List<AppHit>) : ShowStatus
    data class Failed(val code: String) : ShowStatus
}

sealed interface PointerAction {
    data class Tap(val x: Int, val y: Int) : PointerAction
    data class LongClick(val x: Int, val y: Int) : PointerAction
    data class Swipe(
        val x1: Int,
        val y1: Int,
        val x2: Int,
        val y2: Int,
        val durationMs: Long,
    ) : PointerAction

    data class Key(val code: Int) : PointerAction
}
