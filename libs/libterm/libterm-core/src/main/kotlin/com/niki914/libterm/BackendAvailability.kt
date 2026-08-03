package com.niki914.libterm

sealed interface BackendAvailability {
    data object Available : BackendAvailability

    data class Unavailable(
        val failure: TerminalFailure,
    ) : BackendAvailability

    data class Unauthorized(
        val failure: TerminalFailure,
    ) : BackendAvailability
}
