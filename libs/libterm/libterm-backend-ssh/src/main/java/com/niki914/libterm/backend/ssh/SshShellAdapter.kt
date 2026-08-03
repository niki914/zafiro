package com.niki914.libterm.backend.ssh

import com.niki914.libterm.TerminalBytes
import kotlinx.coroutines.flow.Flow

internal interface SshShellAdapter {
    val output: Flow<TerminalBytes>

    suspend fun write(input: TerminalBytes)

    suspend fun close()

    suspend fun awaitExit(): Throwable?
}
