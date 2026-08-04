package com.niki914.libterm.runtime

import com.niki914.libterm.TerminalBytes

data class CommandResult(
    val command: String,
    val stdout: TerminalBytes,
    val stderr: TerminalBytes,
    val exitCode: Int?,
    val timedOut: Boolean,
    val terminationReason: CommandTerminationReason = CommandTerminationReason.COMPLETED,
)
