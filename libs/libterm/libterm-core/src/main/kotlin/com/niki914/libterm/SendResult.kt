package com.niki914.libterm

sealed interface SendResult {
    data object Sent : SendResult

    data class Failed(val failure: TerminalFailure) : SendResult
}
