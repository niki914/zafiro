package com.niki914.libterm

sealed interface OpenResult<out T> {
    data class Success<T>(
        val value: T,
    ) : OpenResult<T>

    data class Failure(
        val failure: TerminalFailure,
    ) : OpenResult<Nothing>
}
