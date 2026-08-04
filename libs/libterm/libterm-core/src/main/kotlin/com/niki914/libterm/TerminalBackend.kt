package com.niki914.libterm

import kotlinx.coroutines.flow.Flow

interface TerminalBackend {
    val identity: TerminalIdentity
    val output: Flow<OutputChunk>

    suspend fun start(
        openOptions: TerminalOpenOptions = TerminalOpenOptions(),
    ): BackendStartResult

    suspend fun send(input: TerminalBytes): SendResult

    suspend fun close()

    suspend fun awaitExit(): TerminalFailure?
}
