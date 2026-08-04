package com.niki914.libterm.runtime

import com.niki914.libterm.SessionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface Term {
    val state: StateFlow<SessionState>
    val stream: Flow<TerminalTextChunk>

    suspend fun open(): TermResult<Unit>

    suspend fun exec(
        command: String,
        timeoutMillis: Long = DEFAULT_EXEC_TIMEOUT_MILLIS,
    ): TermResult<CommandResult>

    suspend fun write(text: String): TermResult<Unit>

    suspend fun write(bytes: ByteArray): TermResult<Unit>

    suspend fun close(): TermResult<Unit>

    companion object {
        const val DEFAULT_EXEC_TIMEOUT_MILLIS: Long = 30_000L
    }
}
