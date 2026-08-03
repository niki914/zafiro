package com.niki914.libterm.runtime

import com.niki914.libterm.TerminalFailure

sealed interface TermResult<out T> {
    data class Success<T>(
        val value: T,
    ) : TermResult<T>

    data class Failure(
        val failure: TerminalFailure,
    ) : TermResult<Nothing>
}
