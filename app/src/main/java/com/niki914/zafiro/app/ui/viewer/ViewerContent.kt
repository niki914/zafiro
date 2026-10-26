package com.niki914.zafiro.app.ui.viewer

/**
 * 查看页面数据契约。
 * 遵循长期主义设计：支持未来平滑扩充 Markdown、图片、网页等类型，当前仅实现文本。
 */
sealed interface ViewerContent {
    val routeKey: String

    /**
     * 纯文本内容查看（例如长用户消息）。
     */
    data class Text(
        val text: String,
    ) : ViewerContent {
        override val routeKey: String get() = "text:${text.hashCode()}"
    }
}
