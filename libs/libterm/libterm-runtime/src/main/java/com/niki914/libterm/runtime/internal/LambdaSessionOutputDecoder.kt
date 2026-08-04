package com.niki914.libterm.runtime.internal

import com.niki914.libterm.OutputChunk
import com.niki914.libterm.runtime.SessionTerminalOutputDecoder
import com.niki914.libterm.runtime.TerminalTextChunk

internal class LambdaSessionOutputDecoder(
    private val decodeText: (OutputChunk) -> String,
) : SessionTerminalOutputDecoder {
    override fun decode(chunk: OutputChunk): List<TerminalTextChunk> {
        return listOf(
            TerminalTextChunk(
                stream = chunk.stream,
                text = decodeText(chunk),
                timestampMillis = chunk.timestampMillis,
            ),
        )
    }

    override fun reset() = Unit
}
