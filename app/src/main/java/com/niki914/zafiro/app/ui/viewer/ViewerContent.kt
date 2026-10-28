package com.niki914.zafiro.app.ui.viewer

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 查看页面数据契约。
 * 遵循长期主义设计：支持未来平滑扩充 Markdown、图片、网页等类型。
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

    /**
     * 图片查看（单图或多图画廊）。
     */
    data class Image(
        val path: String,
        val images: List<String> = listOf(path),
        val initialIndex: Int = 0,
    ) : ViewerContent {
        override val routeKey: String get() = "image:${path.hashCode()}:$initialIndex"

        companion object {
            /** 从画廊的完整路径表 + 被点击位置构造内容，避免调用方各自拼装 path/initialIndex。 */
            fun of(paths: List<String>, index: Int): Image {
                val safeIndex = index.coerceIn(paths.indices)
                return Image(path = paths[safeIndex], images = paths, initialIndex = safeIndex)
            }
        }
    }
}

/**
 * 全局查看器打开回调。
 * 供深层组件（卡片、输入框附件条、长文本折叠等）直接呼出全屏查看器，避免跨层逐级透传。
 * 默认空实现，在 Preview 或未绑定环境下安全无副作用。
 */
val LocalViewerOpener: ProvidableCompositionLocal<(ViewerContent) -> Unit> =
    staticCompositionLocalOf {
        {}
    }
