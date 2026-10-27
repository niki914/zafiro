package com.niki914.zafiro.app.ui.util

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import com.niki914.zafiro.app.conversation.SearchSnippetHelper

/**
 * 搜索关键词高亮辅助工具。
 */
object SearchHighlightHelper {

    /**
     * 将普通文本根据 [query] 转换为带高亮样式的 [AnnotatedString]。
     */
    fun highlight(
        text: String,
        query: String,
        highlightStyle: SpanStyle,
    ): AnnotatedString {
        if (query.isBlank() || text.isEmpty()) {
            return AnnotatedString(text)
        }

        val ranges = SearchSnippetHelper.findMatchRanges(text, query)
        if (ranges.isEmpty()) {
            return AnnotatedString(text)
        }

        return buildAnnotatedString {
            append(text)
            for (range in ranges) {
                addStyle(highlightStyle, range.first, range.last + 1)
            }
        }
    }
}
