package com.niki914.libterm.runtime

import com.niki914.libterm.OutputStream

data class TerminalTextChunk(
    val stream: OutputStream,
    val text: String,
    val timestampMillis: Long,
)
