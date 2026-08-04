package com.niki914.libterm

sealed interface SessionState {
    data object Starting : SessionState

    data object Running : SessionState

    data object Closed : SessionState

    data class Failed(
        val failure: TerminalFailure,
    ) : SessionState
}
