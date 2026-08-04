package com.niki914.libterm

data class TerminalBufferConfig(
    val maxChunkCount: Int = 256,
    val maxByteCount: Int = 65_536,
) {
    init {
        require(maxChunkCount > 0) { "maxChunkCount must be greater than 0" }
        require(maxByteCount > 0) { "maxByteCount must be greater than 0" }
    }
}
