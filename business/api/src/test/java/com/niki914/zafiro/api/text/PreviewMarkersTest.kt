// 保护：正文预览去掉 * # ` _ ~ >；漏掉星号会重新占满列表和通知的一行，多删减号会改掉正文里的负号
package com.niki914.zafiro.api.text

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewMarkersTest {

    @Test
    fun strip_removesMarkerRunsAndKeepsTheWords() {
        assertEquals("核心原理：在内核", PreviewMarkers.strip("核心原理：**在内核"))
    }

    @Test
    fun strip_keepsMinusAndPipe() {
        assertEquals("a-b | c", PreviewMarkers.strip("a-b | c"))
    }
}
