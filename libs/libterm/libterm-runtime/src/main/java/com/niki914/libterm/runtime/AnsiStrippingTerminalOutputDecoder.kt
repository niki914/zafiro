package com.niki914.libterm.runtime

import com.niki914.libterm.OutputChunk
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

class AnsiStrippingTerminalOutputDecoder : TerminalOutputDecoder {
    override fun createSessionDecoder(): SessionTerminalOutputDecoder {
        return StrippingSessionTerminalOutputDecoder()
    }

    private class StrippingSessionTerminalOutputDecoder : SessionTerminalOutputDecoder {
        private val utf8Decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)

        private var pendingBytes: ByteArray = ByteArray(0)
        private var escapeState: EscapeState = EscapeState.Text
        private val lineBuffer = StringBuilder()
        private var lineCursor: Int = 0
        private var emittedLineLength: Int = 0

        override fun decode(chunk: OutputChunk): List<TerminalTextChunk> {
            val decodedText = decodeUtf8(chunk)
            if (decodedText.isEmpty()) {
                return emptyList()
            }

            val plainText = stripEscapeSequences(decodedText)
            if (plainText.isEmpty()) {
                return emptyList()
            }

            val normalizedText = applyCarriageReturn(plainText)
            if (normalizedText.isEmpty()) {
                return emptyList()
            }

            return listOf(
                TerminalTextChunk(
                    stream = chunk.stream,
                    text = normalizedText,
                    timestampMillis = chunk.timestampMillis,
                ),
            )
        }

        override fun reset() {
            utf8Decoder.reset()
            pendingBytes = ByteArray(0)
            escapeState = EscapeState.Text
            lineBuffer.setLength(0)
            lineCursor = 0
            emittedLineLength = 0
        }

        private fun decodeUtf8(chunk: OutputChunk): String {
            val bytes = chunk.bytes.toByteArray()
            if (bytes.isEmpty() && pendingBytes.isEmpty()) {
                return ""
            }

            val inputBytes = if (pendingBytes.isEmpty()) {
                bytes
            } else {
                ByteArray(pendingBytes.size + bytes.size).also { merged ->
                    pendingBytes.copyInto(merged, destinationOffset = 0)
                    bytes.copyInto(merged, destinationOffset = pendingBytes.size)
                }
            }

            val inputBuffer = ByteBuffer.wrap(inputBytes)
            val charBuffer = CharBuffer.allocate(maxOf(32, inputBytes.size * 2))
            val output = StringBuilder()

            while (true) {
                val result = utf8Decoder.decode(inputBuffer, charBuffer, false)
                if (charBuffer.position() > 0) {
                    charBuffer.flip()
                    output.append(charBuffer)
                    charBuffer.clear()
                }
                when {
                    result.isOverflow -> continue
                    result.isUnderflow -> break
                    result.isError -> result.throwException()
                }
            }

            pendingBytes = ByteArray(inputBuffer.remaining()).also { remaining ->
                inputBuffer.get(remaining)
            }
            return output.toString()
        }

        private fun stripEscapeSequences(text: String): String {
            val output = StringBuilder(text.length)
            for (char in text) {
                when (escapeState) {
                    EscapeState.Text -> {
                        if (char == ESC) {
                            escapeState = EscapeState.Escape
                        } else {
                            output.append(char)
                        }
                    }

                    EscapeState.Escape -> {
                        escapeState = when (char) {
                            '[' -> EscapeState.Csi
                            ']' -> EscapeState.Osc
                            else -> EscapeState.Text
                        }
                    }

                    EscapeState.Csi -> {
                        if (char in '@'..'~') {
                            escapeState = EscapeState.Text
                        }
                    }

                    EscapeState.Osc -> {
                        escapeState = when (char) {
                            BEL -> EscapeState.Text
                            ESC -> EscapeState.OscEscape
                            else -> EscapeState.Osc
                        }
                    }

                    EscapeState.OscEscape -> {
                        escapeState = if (char == '\\') {
                            EscapeState.Text
                        } else {
                            EscapeState.Osc
                        }
                    }
                }
            }
            return output.toString()
        }

        private fun applyCarriageReturn(text: String): String {
            val output = StringBuilder(text.length)
            val pendingLineOutput = StringBuilder()
            var lineNeedsSnapshot = false

            fun flushLine(includeNewline: Boolean) {
                if (lineNeedsSnapshot) {
                    if (emittedLineLength == 0 || includeNewline) {
                        output.append(lineBuffer)
                    }
                } else {
                    output.append(pendingLineOutput)
                }

                if (includeNewline) {
                    output.append('\n')
                    lineBuffer.setLength(0)
                    lineCursor = 0
                    emittedLineLength = 0
                    lineNeedsSnapshot = false
                } else if (lineNeedsSnapshot && emittedLineLength == 0) {
                    emittedLineLength = lineBuffer.length
                } else if (!lineNeedsSnapshot) {
                    emittedLineLength += pendingLineOutput.length
                }

                pendingLineOutput.setLength(0)
            }

            for (char in text) {
                when (char) {
                    '\r' -> {
                        lineCursor = 0
                        lineNeedsSnapshot = true
                        pendingLineOutput.setLength(0)
                    }

                    '\n' -> {
                        flushLine(includeNewline = true)
                    }

                    else -> {
                        val replacingExisting = lineCursor < lineBuffer.length
                        if (lineCursor < lineBuffer.length) {
                            lineBuffer.setCharAt(lineCursor, char)
                        } else {
                            if (lineCursor > lineBuffer.length) {
                                repeat(lineCursor - lineBuffer.length) {
                                    lineBuffer.append(' ')
                                }
                            }
                            lineBuffer.append(char)
                        }

                        if (!lineNeedsSnapshot && !replacingExisting) {
                            pendingLineOutput.append(char)
                        }
                        lineCursor += 1
                    }
                }
            }
            flushLine(includeNewline = false)
            return output.toString()
        }

        private enum class EscapeState {
            Text,
            Escape,
            Csi,
            Osc,
            OscEscape,
        }

        private companion object {
            const val ESC: Char = '\u001B'
            const val BEL: Char = '\u0007'
        }
    }
}
