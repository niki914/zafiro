// 保护：选中文本清洗（BOM、零宽、换行归一）、Prompt 引用格式拼接契约、截图失败 NOTE 前置及其失败原因回传
package com.niki914.zafiro.app.trigger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextActionPromptComposerTest {

    @Test
    fun compose_quotesSelectionAndAppendsTemplate() {
        val result = TextActionPromptComposer.compose(
            template = "Translate the following text into English.",
            selectedText = "Hello world",
        )

        assertEquals(
            """
            > [NOTE]: The text below was copied from another app.
            >
            > Hello world

            Translate the following text into English.
            """.trimIndent(),
            result,
        )
    }

    @Test
    fun compose_quotesEveryLineOfMultilineSelection() {
        val result = TextActionPromptComposer.compose(
            template = "Summarize.",
            selectedText = "line one\nline two",
        )

        assertEquals(
            """
            > [NOTE]: The text below was copied from another app.
            >
            > line one
            > line two

            Summarize.
            """.trimIndent(),
            result,
        )
    }

    @Test
    fun compose_captureFailedPrependsNoteBeforeQuote() {
        val result = TextActionPromptComposer.compose(
            template = "Explain the screen.",
            selectedText = "Hello world",
            captureFailed = true,
        )

        assertEquals(
            """
            > [NOTE]: Zafiro's screen capture failed; no screenshot is attached.
            > [NOTE]: The text below was copied from another app.
            >
            > Hello world

            Explain the screen.
            """.trimIndent(),
            result,
        )
    }

    @Test
    fun compose_captureFailedWithoutSelectionStillNotes() {
        val result = TextActionPromptComposer.compose(
            template = "Explain the screen.",
            selectedText = null,
            captureFailed = true,
        )

        assertEquals(
            """
            > [NOTE]: Zafiro's screen capture failed; no screenshot is attached.

            Explain the screen.
            """.trimIndent(),
            result,
        )
    }

    @Test
    fun compose_captureFailureReasonJoinsCaptureNote() {
        // 无障碍不可用时，失败原因里那句“指引用户去开无障碍”必须原样进 prompt，
        // 否则模型只会说“截图失败了”，用户不知道该怎么办。
        val result = TextActionPromptComposer.compose(
            template = "Explain the screen.",
            selectedText = "Hello world",
            captureFailed = true,
            captureFailure =
                "The Zafiro accessibility service is not available. " +
                        "Tell the user to enable 'Zafiro' in Settings > Accessibility, then retry.",
        )

        assertEquals(
            """
            > [NOTE]: Zafiro's screen capture failed; no screenshot is attached. The Zafiro accessibility service is not available. Tell the user to enable 'Zafiro' in Settings > Accessibility, then retry.
            > [NOTE]: The text below was copied from another app.
            >
            > Hello world

            Explain the screen.
            """.trimIndent(),
            result,
        )
    }

    @Test
    fun compose_withoutSelectionReturnsTemplateOnly() {
        assertEquals(
            "Summarize.",
            TextActionPromptComposer.compose("Summarize.", null),
        )
        assertEquals(
            "Summarize.",
            TextActionPromptComposer.compose("Summarize.", "   "),
        )
    }
}

class TextSelectionInputTest {

    @Test
    fun parse_nullIsRejected() {
        assertNull(TextSelectionInput.parse(null))
    }

    @Test
    fun parse_blankIsRejected() {
        assertNull(TextSelectionInput.parse("   \n\t "))
    }

    @Test
    fun parse_invisibleCharactersOnlyIsRejected() {
        // BOM + zero-width space + word joiner
        assertNull(TextSelectionInput.parse("\uFEFF\u200B\u2060"))
    }

    @Test
    fun parse_stripsBomAndNormalizesLineEndings() {
        assertEquals("a\nb", TextSelectionInput.parse("\uFEFFa\r\nb"))
    }

    @Test
    fun parse_preservesLeadingWhitespaceOfRealContent() {
        // 段落缩进是内容，trim 只用于判空
        assertEquals("    indented", TextSelectionInput.parse("    indented"))
    }
}
