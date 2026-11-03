// 保护：波斯语/阿拉伯语聊天文本按首个强方向字符判为 RTL；只看首字符、漏掉波斯语专有字母、或把数字/Markdown 前缀当成 LTR 的改动都会让波斯语消息重新变回左对齐
package com.niki914.zafiro.app.ui.chat.helper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RtlTextTest {

    @Test
    fun persianText_isRtl() {
        assertTrue(isRtlText("سلام، چطوری؟"))
    }

    @Test
    fun persianOnlyLetters_isRtl() {
        assertTrue(isRtlText("پچگژ"))
    }

    @Test
    fun arabicText_isRtl() {
        assertTrue(isRtlText("مرحبا، كيف حالك؟"))
    }

    @Test
    fun englishText_isLtr() {
        assertFalse(isRtlText("Hello, how are you?"))
    }

    @Test
    fun markdownPrefix_beforePersian_isRtl() {
        assertTrue(isRtlText("# سلام"))
        assertTrue(isRtlText("- سلام"))
    }

    @Test
    fun digitsBeforePersian_isRtl() {
        assertTrue(isRtlText("123 سلام"))
    }

    @Test
    fun englishFirst_thenPersian_isLtr() {
        assertFalse(isRtlText("Hello سلام"))
    }

    @Test
    fun emptyText_isLtr() {
        assertFalse(isRtlText(""))
    }
}
