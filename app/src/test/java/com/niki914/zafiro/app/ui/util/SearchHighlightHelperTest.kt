// 保护：Compose 文本搜索关键词高亮样式区间计算，防止空串或多处命中样式偏移
package com.niki914.zafiro.app.ui.util

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchHighlightHelperTest {

    @Test
    fun highlight_appliesSpanStyleToAllMatchingRanges() {
        val text = "Kotlin is awesome, kotlin is fun"
        val style = SpanStyle(fontWeight = FontWeight.Bold)

        val annotated = SearchHighlightHelper.highlight(text, "kotlin", style)

        assertEquals(text, annotated.text)
        assertEquals(2, annotated.spanStyles.size)
        assertEquals(0, annotated.spanStyles[0].start)
        assertEquals(6, annotated.spanStyles[0].end)
        assertEquals(19, annotated.spanStyles[1].start)
        assertEquals(25, annotated.spanStyles[1].end)
    }

    @Test
    fun highlight_returnsPlainAnnotatedStringWhenQueryBlankOrNotFound() {
        val text = "Plain text without matches"
        val style = SpanStyle(fontWeight = FontWeight.Bold)

        val annotatedBlank = SearchHighlightHelper.highlight(text, "", style)
        val annotatedNotFound = SearchHighlightHelper.highlight(text, "missing", style)

        assertTrue(annotatedBlank.spanStyles.isEmpty())
        assertTrue(annotatedNotFound.spanStyles.isEmpty())
        assertEquals(text, annotatedBlank.text)
    }
}
