package com.niki914.libterm.backend.shizuku.internal

import com.niki914.libterm.SendResult
import com.niki914.libterm.TerminalBytes
import com.niki914.libterm.TerminalFailure
import kotlinx.coroutines.flow.Flow

internal interface ShizukuShellClientFactory {
    suspend fun open(cwd: String): ShizukuShellClient
}

internal interface ShizukuShellClient {
    val output: Flow<ShizukuShellOutputEvent>

    suspend fun write(input: TerminalBytes): SendResult

    suspend fun close()

    suspend fun awaitExit(): TerminalFailure?
}

internal sealed interface ShizukuShellOutputEvent {
    data class Stdout(val bytes: TerminalBytes) : ShizukuShellOutputEvent

    data class Stderr(val bytes: TerminalBytes) : ShizukuShellOutputEvent
}
