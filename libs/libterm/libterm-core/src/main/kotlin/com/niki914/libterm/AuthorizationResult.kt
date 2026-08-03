package com.niki914.libterm

sealed interface AuthorizationResult {
    data object Granted : AuthorizationResult

    data class Denied(
        val failure: TerminalFailure.AuthorizationDenied,
    ) : AuthorizationResult

    data class Unavailable(
        val failure: TerminalFailure.BackendUnavailable,
    ) : AuthorizationResult

    data class Failed(
        val failure: TerminalFailure.AuthorizationFailed,
    ) : AuthorizationResult
}
