package com.niki914.libterm

data class OutputChunk(
    val stream: OutputStream,
    val bytes: TerminalBytes,
    val timestampMillis: Long,
)
