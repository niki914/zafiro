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
    fun list(): List<ScreenInfo>

    fun get(id: ScreenId): Screen?

    suspend fun open(kind: OpenKind): OpenResult

    suspend fun close(id: ScreenId): CloseResult
}

open class ScreenTreeUnavailable(message: String) : RuntimeException(message)

open class ScreenImageUnavailable(
    val code: String,
    message: String? = null,
) : RuntimeException(message ?: code)

