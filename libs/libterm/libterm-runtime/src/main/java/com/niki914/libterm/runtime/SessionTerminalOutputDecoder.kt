package com.niki914.libterm.runtime

import com.niki914.libterm.OutputChunk

interface SessionTerminalOutputDecoder {
    fun decode(chunk: OutputChunk): List<TerminalTextChunk>

    fun reset()
}
