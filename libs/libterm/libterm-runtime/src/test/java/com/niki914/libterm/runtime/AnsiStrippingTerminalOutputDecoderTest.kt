package com.niki914.libterm.runtime

import com.niki914.libterm.OutputChunk
import com.niki914.libterm.OutputStream
import com.niki914.libterm.TerminalBytes
import com.niki914.libterm.runtime.internal.LambdaSessionOutputDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame

class AnsiStrippingTerminalOutputDecoderTest {

    @Test
    fun `createSessionDecoder returns isolated decoder instances`() {
        val outputDecoder = AnsiStrippingTerminalOutputDecoder()
        val first = outputDecoder.createSessionDecoder()
        val second = outputDecoder.createSessionDecoder()

        first.decode(chunk("\u001B[31"))
        val secondText = second.decode(chunk("plain"))

        assertNotSame(first, second)
        assertEquals(listOf("plain"), secondText.map(TerminalTextChunk::text))
    }

    @Test
    fun `ansi decoder strips csi osc and single escape sequences across chunks`() {
        val decoder = AnsiStrippingTerminalOutputDecoder().createSessionDecoder()

        val first = decoder.decode(chunk("hello\u001B[31"))
        val second = decoder.decode(chunk("mred\u001B]0;ti"))
        val third = decoder.decode(chunk("tle"))
        val fourth = decoder.decode(chunk("\u0007world\u001B7"))

        assertEquals(listOf("hello"), first.map(TerminalTextChunk::text))
        assertEquals(listOf("red"), second.map(TerminalTextChunk::text))
        assertEquals(emptyList(), third.map(TerminalTextChunk::text))
        assertEquals(listOf("world"), fourth.map(TerminalTextChunk::text))
    }

    @Test
    fun `ansi decoder applies carriage return overwrite semantics`() {
        val decoder = AnsiStrippingTerminalOutputDecoder().createSessionDecoder()

        val rendered = decoder.decode(chunk("hello\rxy\n"))

        assertEquals(listOf("xyllo\n"), rendered.map(TerminalTextChunk::text))
    }

    @Test
    fun `lambda session decoder wraps text and preserves metadata`() {
        val decoder = LambdaSessionOutputDecoder { outputChunk ->
            outputChunk.bytes.toByteArray().decodeToString().uppercase()
        }

        val rendered = decoder.decode(
            OutputChunk(
                stream = OutputStream.STDERR,
                bytes = TerminalBytes.of("hello".encodeToByteArray()),
                timestampMillis = 123L,
            ),
        )

        assertEquals(
            listOf(
                TerminalTextChunk(
                    stream = OutputStream.STDERR,
                    text = "HELLO",
                    timestampMillis = 123L,
                ),
            ),
            rendered,
        )
    }

    private fun chunk(
        text: String,
        stream: OutputStream = OutputStream.STDOUT,
        timestampMillis: Long = 100L,
    ): OutputChunk {
        return OutputChunk(
            stream = stream,
            bytes = TerminalBytes.of(text.encodeToByteArray()),
            timestampMillis = timestampMillis,
        )
    }
}
