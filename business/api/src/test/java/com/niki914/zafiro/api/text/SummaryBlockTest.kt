// 保护：<zfr-summary> 块的结构化编解码、元数据解析容错与前缀剥除契约；畸形格式、缺失标签与多块共存不得引发崩溃或正文污染
package com.niki914.zafiro.api.text

import com.niki914.zafiro.api.model.FileRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SummaryBlockTest {

    @Test
    fun `summary block round trips with complex markdown`() {
        val markdown = """
            ## Goal
            Build conversation compaction.

            ## Key Decisions
            - Use `<zfr-summary>` tag aligned with Pi.
            - Pure logic pruning.

            ```kotlin
            fun test() = println("hello")
            ```
        """.trimIndent()
        val payload = SummaryBlock.Payload(keepTurns = 2, summary = markdown)
        val block = SummaryBlock.block(payload)

        val stripped = SummaryBlock.strip(block)
        assertEquals(payload, stripped.payload)
        assertEquals("", stripped.text)
    }

    @Test
    fun `strip removes summary block and leaves clean user text`() {
        val payload = SummaryBlock.Payload(keepTurns = 1, summary = "Short summary")
        val fullText = "${SummaryBlock.block(payload)}\n\n接下来请继续"

        val stripped = SummaryBlock.strip(fullText)
        assertEquals("接下来请继续", stripped.text)
        assertEquals(payload, stripped.payload)
        assertTrue(SummaryBlock.hasBlock(fullText))
    }

    @Test
    fun `hasBlock returns false when text has no summary block`() {
        val text = "普通的没有 summary 块的用户发言"
        val stripped = SummaryBlock.strip(text)
        assertEquals(text, stripped.text)
        assertNull(stripped.payload)
        assertFalse(SummaryBlock.hasBlock(text))
    }

    @Test
    fun `parse rejects missing or invalid keep turns`() {
        val noKeepTurns = "<summary>\nfoo\n</summary>"
        val invalidKeepTurns = "keep-turns: not_a_number\n\n<summary>\nfoo\n</summary>"
        val negativeKeepTurns = "keep-turns: -1\n\n<summary>\nfoo\n</summary>"

        assertNull(SummaryBlock.parse(noKeepTurns))
        assertNull(SummaryBlock.parse(invalidKeepTurns))
        assertNull(SummaryBlock.parse(negativeKeepTurns))
    }

    @Test
    fun `parse rejects unclosed or reversed summary tags`() {
        val unclosed = "keep-turns: 2\n\n<summary>\nfoo"
        val noOpen = "keep-turns: 2\n\nfoo\n</summary>"
        val reversed = "keep-turns: 2\n\n</summary>\nfoo\n<summary>"

        assertNull(SummaryBlock.parse(unclosed))
        assertNull(SummaryBlock.parse(noOpen))
        assertNull(SummaryBlock.parse(reversed))
    }

    @Test
    fun `coexists with files block without interference`() {
        val files = listOf(FileRef("/storage/emulated/0/Download/spec.pdf"))
        val filesBlock = FilesBlock.block(files)!!
        val summaryPayload = SummaryBlock.Payload(keepTurns = 3, summary = "Context summary")
        val summaryBlock = SummaryBlock.block(summaryPayload)

        val combinedText = "$filesBlock\n\n$summaryBlock\n\n请分析这份文档"

        val summaryStripped = SummaryBlock.strip(combinedText)
        val filesStripped = FilesBlock.strip(combinedText)

        assertEquals("请分析这份文档", summaryStripped.text)
        assertEquals(summaryPayload, summaryStripped.payload)

        assertEquals("请分析这份文档", filesStripped.text)
        assertEquals(files, filesStripped.files)
    }
}
