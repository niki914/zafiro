// 保护：在库生成的 AnnotatedString 上插入隔离符时，链接覆盖的文字与段落范围保持不变；按片复制段落样式会多出段落边界。
package com.niki914.uikit.text

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class BidiAnnotatedStringTest {

    @Test
    fun `islands are isolated while link and span ranges keep covering the same text`() {
        val source = buildAnnotatedString {
            append("קרא ")
            withLink(LinkAnnotation.Url("https://example.com")) { append("src/main.kt") }
            append(" עכשיו")
            addStyle(SpanStyle(fontWeight = FontWeight.Bold), 0, 3)
        }

        val isolated = source.isolateLtrIslands()

        assertEquals("קרא ⁦src/main.kt⁩ עכשיו", isolated.text)
        val link = isolated.getLinkAnnotations(0, isolated.length).single()
        assertEquals("src/main.kt", isolated.text.substring(link.start, link.end))
    }

    @Test
    fun `one paragraph style stays one paragraph`() {
        val source = buildAnnotatedString {
            append("קרא src/main.kt עכשיו")
            addStyle(ParagraphStyle(textAlign = TextAlign.Start), 0, length)
        }

        val isolated = source.isolateLtrIslands()

        val paragraph = isolated.paragraphStyles.single()
        assertEquals(0 until isolated.length, paragraph.start until paragraph.end)
    }

    @Test
    fun `text without islands is returned as is`() {
        val source = buildAnnotatedString { append("שלום עולם") }

        assertSame(source, source.isolateLtrIslands())
    }
}
