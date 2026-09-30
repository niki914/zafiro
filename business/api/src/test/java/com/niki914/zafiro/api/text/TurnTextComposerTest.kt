package com.niki914.zafiro.api.text

import com.niki914.zafiro.api.model.FileRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 注入块的往返与边界。这套语法是提示词注入的承重墙：切多了会把用户的话吞掉，
 * 切少了会把 `<zfr-files>` 灌进输入框，所以边界用例钉死。
 */
class TurnTextComposerTest {

    private val pdf = "/storage/emulated/0/Download/report.pdf"
    private val logsDir = "/storage/emulated/0/adbi/pkg/logs/"

    @Test
    fun `files block round trips`() {
        val block = FilesBlock.block(listOf(FileRef(pdf), FileRef(logsDir)))
        assertTrue(block != null)

        val cut = TurnTextComposer.cutLeadingBlocks("$block\n\n看一下这个")
        assertEquals("看一下这个", cut.text)
        assertEquals(listOf("files"), cut.blocks.map { it.tag })
        assertEquals(listOf(FileRef(pdf), FileRef(logsDir)), FilesBlock.parse(cut.blocks.single().body))
    }

    @Test
    fun `entries carry the file or folder prefix`() {
        val block = FilesBlock.block(listOf(FileRef(pdf), FileRef(logsDir)))!!
        assertTrue(block.contains("The user attached these paths:"))
        assertTrue(block.contains("- file: $pdf"))
        assertTrue(block.contains("- folder: $logsDir"))
    }

    @Test
    fun `empty file list produces no block`() {
        assertNull(FilesBlock.block(emptyList()))
    }

    @Test
    fun `block with a single file still parses`() {
        val block = FilesBlock.block(listOf(FileRef(pdf)))!!
        assertEquals(listOf(FileRef(pdf)), FilesBlock.parse(TurnTextComposer.cutLeadingBlocks(block).blocks.single().body))
    }

    @Test
    fun `explanation lines inside the payload are ignored`() {
        val block = TurnTextComposer.wrapBlock(
            FilesBlock.TAG,
            "随便写点说明\n- file: $pdf\n- 这行没前缀，不是条目\n- folder: $logsDir",
        )
        assertEquals(
            listOf(FileRef(pdf), FileRef(logsDir)),
            FilesBlock.parse(TurnTextComposer.cutLeadingBlocks(block).blocks.single().body),
        )
    }

    @Test
    fun `several leading blocks are all cut`() {
        val first = TurnTextComposer.wrapBlock("files", "- $pdf")
        val second = TurnTextComposer.wrapBlock("skill", "skill 正文")
        val cut = TurnTextComposer.cutLeadingBlocks("$first\n\n$second\n\n正文")
        assertEquals(listOf("files", "skill"), cut.blocks.map { it.tag })
        assertEquals("正文", cut.text)
    }

    @Test
    fun `text without blocks is returned byte identical`() {
        val text = "普通的一句话\n\n第二段"
        val cut = TurnTextComposer.cutLeadingBlocks(text)
        assertEquals(text, cut.text)
        assertTrue(cut.blocks.isEmpty())
    }

    @Test
    fun `empty text is not touched`() {
        assertEquals(TurnTextComposer.Cut("", emptyList()), TurnTextComposer.cutLeadingBlocks(""))
    }

    @Test
    fun `a block in the middle of the text is not cut`() {
        val block = TurnTextComposer.wrapBlock("files", "- $pdf")
        val text = "先说话\n$block"
        val cut = TurnTextComposer.cutLeadingBlocks(text)
        assertEquals(text, cut.text)
        assertTrue(cut.blocks.isEmpty())
    }

    @Test
    fun `an unclosed opening tag is left alone`() {
        // 用户自己敲了个开标签但没闭合：整段原样留着，不做任何猜测
        val text = "<zfr-files>\n- $pdf\n后面没了"
        val cut = TurnTextComposer.cutLeadingBlocks(text)
        assertEquals(text, cut.text)
        assertTrue(cut.blocks.isEmpty())
    }

    @Test
    fun `a non tag line stops the cutting`() {
        val text = "插了句嘴\n<zfr-files>\n- $pdf\n</zfr-files>"
        val cut = TurnTextComposer.cutLeadingBlocks(text)
        assertEquals(text, cut.text)
        assertTrue(cut.blocks.isEmpty())
    }

    @Test
    fun `closing tag must sit on its own line`() {
        val text = "<zfr-files>\n- $pdf\n</zfr-files> 尾巴"
        val cut = TurnTextComposer.cutLeadingBlocks(text)
        assertEquals(text, cut.text)
        assertTrue(cut.blocks.isEmpty())
    }

    @Test
    fun `an opening line that is not exactly a tag is not a block`() {
        // 前缀对但形态不对：`<zfr-files >` 不是合法开标签
        val text = "<zfr-files >\n- $pdf\n</zfr-files>"
        assertEquals(text, TurnTextComposer.cutLeadingBlocks(text).text)
        // 前缀不对的更不算
        val foreign = "<zafiro-files>\n- $pdf\n</zafiro-files>"
        assertEquals(foreign, TurnTextComposer.cutLeadingBlocks(foreign).text)
    }

    @Test
    fun `block cut without a trailing blank line still leaves clean text`() {
        val block = TurnTextComposer.wrapBlock("files", "- $pdf")
        val cut = TurnTextComposer.cutLeadingBlocks("$block\n正文")
        assertEquals("正文", cut.text)
    }

    @Test
    fun `block at the very end leaves empty text`() {
        val block = TurnTextComposer.wrapBlock("files", "- $pdf")
        assertEquals("", TurnTextComposer.cutLeadingBlocks(block).text)
    }

    @Test
    fun `hand written payloads without prefixes are ignored`() {
        // 用户自己在正文里敲的裸列表不是引用：没有前缀就不认
        assertEquals(emptyList<FileRef>(), FilesBlock.parse("The user attached these paths:\n- $pdf\n- "))
    }
}
