package com.niki914.zafiro.api.screen

/**
 * 一块可看、可点、可换内容的屏。
 *
 * 手机和虚拟屏的实现是 [DisplayScreen]。应用内浏览器的实现是 [BrowserScreen]。
 */
interface Screen {
    val id: ScreenId
    val kind: ScreenKind

    suspend fun snapshot(): ScreenSnapshot

    suspend fun image(): ImageFrame

    suspend fun act(action: ScreenAction): ActResult

    suspend fun show(target: ShowTarget): ShowResult
}

sealed interface OpenResult {
    data class Opened(val info: ScreenInfo) : OpenResult
    data class Unsupported(val kind: OpenKind) : OpenResult
    data class Rejected(val code: String) : OpenResult
}

sealed interface CloseResult {
    data object Closed : CloseResult
    data class Rejected(val code: String) : CloseResult
}

/**
 * 屏的目录。进程内通过 `findService<Screens>()` 取得。
 *
 * `phone` 从目录诞生起就在。`open` 不接受手机。`close` 拒绝关掉手机。
 */
interface Screens {
    fun list(callerConversationId: String? = null): List<ScreenInfo>

    fun get(id: ScreenId, callerConversationId: String? = null): Screen?

    suspend fun open(kind: OpenKind, ownerConversationId: String? = null): OpenResult

    suspend fun close(id: ScreenId, callerConversationId: String? = null): CloseResult

    suspend fun closeForConversation(conversationId: String): CloseResult =
        CloseResult.Rejected("UNKNOWN_SCREEN")

    fun launchApp(id: ScreenId, packageName: String) {}

    fun bindForegroundConversation(conversationId: String?) {}

    fun onConversationDestroyed(conversationId: String) {}

    fun hasVirtualScreen(conversationId: String?): Boolean = false

    fun isOverlayShowing(conversationId: String?): Boolean = false

    fun reopenOverlay(conversationId: String?): Boolean = false
}

open class ScreenTreeUnavailable(message: String) : RuntimeException(message)

open class ScreenImageUnavailable(
    val code: String,
    message: String? = null,
) : RuntimeException(message ?: code)

