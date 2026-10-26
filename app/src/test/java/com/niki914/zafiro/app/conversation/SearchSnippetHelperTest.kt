// 保护：单行会话预览中文本截断不遗漏命中关键词，以及大小写敏感与边界字符处理
package com.niki914.zafiro.app.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchSnippetHelperTest {

    @Test
    fun extractSnippet_centersAroundQueryWhenLocatedInMiddle() {
        val longPrefix = "这是一个非常冗长且包含很多背景说明的前置上下文文本内容，旨在模拟很长的消息"
        val longSuffix = "后面跟着同样很长的一大堆后续补充说明文本信息，防止在没有截断时直接显示"
        val text = "$longPrefix 特殊标记词 $longSuffix"

        val snippet = SearchSnippetHelper.extractSnippet(text, "特殊标记词", radius = 10)

        assertTrue(snippet.startsWith("..."))
        assertTrue(snippet.endsWith("..."))
        assertTrue(snippet.contains("特殊标记词"))
    }

    @Test
    fun extractSnippet_hasNoLeadingEllipsisWhenQueryAtStart() {
        val text = "关机指令已成功接收，正在准备挂起后台进程"

        val snippet = SearchSnippetHelper.extractSnippet(text, "关机", radius = 10)

        assertTrue(snippet.startsWith("关机"))
        assertTrue(snippet.endsWith("..."))
    }

    @Test
    fun extractSnippet_hasNoTrailingEllipsisWhenQueryAtEnd() {
        val text = "已检查所有正在运行的前台服务，全部已关机"

        val snippet = SearchSnippetHelper.extractSnippet(text, "关机", radius = 10)

        assertTrue(snippet.startsWith("..."))
        assertTrue(snippet.endsWith("关机"))
    }

    @Test
    fun extractSnippet_flattensMultipleWhitespacesAndNewlines() {
        val text = "第一行文本\n\n\t第二行带有   大量空格以及关键词在最后"

        val snippet = SearchSnippetHelper.extractSnippet(text, "关键词", radius = 10)

        assertEquals(false, snippet.contains("\n"))
        assertEquals(false, snippet.contains("\t"))
        assertTrue(snippet.contains("带有 大量空格以及关键词"))
    }

    @Test
    fun extractSnippet_matchesCaseInsensitive() {
        val text = "Detailed analysis of the SCREEN capture results"

        val snippet = SearchSnippetHelper.extractSnippet(text, "screen", radius = 8)

        assertTrue(snippet.contains("SCREEN"))
    }

    @Test
    fun extractSnippet_returnsLeadingWindowWhenQueryBlankOrNotFound() {
        val text = "这是一段用于测试未命中或者空关键词的示例文本内容，长度略长于两倍半径"

        val snippet = SearchSnippetHelper.extractSnippet(text, "不存在的词", radius = 8)

        assertTrue(snippet.endsWith("..."))
        assertTrue(snippet.startsWith("这是一段用于测试"))
    }

    @Test
    fun extractSnippet_stripsMarkdownFormatting() {
        val text = "### 标题说明\n\n这是一个**重要提示**，请务必留意"

        val snippet = SearchSnippetHelper.extractSnippet(text, "重要提示")

        assertEquals(false, snippet.contains("*"))
        assertEquals(false, snippet.contains("#"))
        assertTrue(snippet.contains("重要提示"))
    }

    @Test
    fun extractSnippet_asymmetricDefaultKeepsKeywordCloseToLeadingEdge() {
        val longPrefix = "这是一个很长很长很长很长的前置背景介绍"
        val text = "$longPrefix，核心词汇在这里出现"

        val snippet = SearchSnippetHelper.extractSnippet(text, "核心词汇")

        // 默认 leadingRadius 为 5，前面只有 "..." + 5 个字，确保关键词不会被单行截断挤出视野
        val index = snippet.indexOf("核心词汇")
        assertTrue("关键词应位于开头附近，实际 index: $index", index in 3..9)
    }

    @Test
    fun findMatchRanges_findsAllOccurrencesCaseInsensitive() {
        val text = "Hello world, HELLO android, hello zafiro"

        val ranges = SearchSnippetHelper.findMatchRanges(text, "hello")

        assertEquals(3, ranges.size)
        assertEquals(0 until 5, ranges[0])
        assertEquals(13 until 18, ranges[1])
        assertEquals(28 until 33, ranges[2])
    }

    @Test
    fun findMatchRanges_returnsEmptyWhenQueryIsBlank() {
        val ranges = SearchSnippetHelper.findMatchRanges("Some text", "   ")

        assertTrue(ranges.isEmpty())
    }
}
