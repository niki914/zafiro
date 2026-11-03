package com.niki914.zafiro.app.ui.chat.helper

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

/**
 * 按首个强方向字符判定一段聊天文本是否以 RTL 书写（波斯语/阿拉伯语等）。
 * 跳过 Markdown 符号、数字、标点等中性字符：一段以 "# " 或 "1. " 开头的波斯语
 * 段落，方向仍由它第一个波斯语/阿拉伯语字母决定。
 */
internal fun isRtlText(text: String): Boolean {
    for (ch in text) {
        when (Character.getDirectionality(ch)) {
            Character.DIRECTIONALITY_RIGHT_TO_LEFT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
            -> return true

            Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return false
        }
    }
    return false
}

/**
 * 聊天文本方向容器：RTL 文本在 RTL 布局方向下渲染（TextAlign.Start 落到右侧，
 * 列表符号、表格列序随之右对齐），LTR 文本保持原样。只影响文本方向，
 * 不改气泡位置、不做任何语言本地化。
 */
@Composable
internal fun ChatTextDirection(
    text: String,
    content: @Composable () -> Unit,
) {
    if (remember(text) { isRtlText(text) }) {
        CompositionLocalProvider(
            LocalLayoutDirection provides LayoutDirection.Rtl,
        ) {
            content()
        }
    } else {
        content()
    }
}
