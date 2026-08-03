package com.niki914.libterm.backend.ssh

import com.niki914.libterm.SshOpenOptions
import com.niki914.libterm.TerminalFailure
import com.niki914.libterm.TerminalIdentity
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

internal object JschFailureMapper {
    fun mapStartFailure(
        options: SshOpenOptions,
        error: Throwable,
    ): TerminalFailure {
        if (error is SshInvalidOpenOptionsException) {
            return error.failure
        }

        val message = error.message
        return when {
            error.isAuthenticationFailure() -> TerminalFailure.SshAuthenticationFailed(
                message = message,
                username = options.username,
                cause = error,
            )

            error.isHostKeyFailure() -> TerminalFailure.SshHostKeyVerificationFailed(
                message = message,
                host = options.host,
                cause = error,
            )

            error.isConnectionFailure() -> TerminalFailure.SshConnectionFailed(
                message = message,
                host = options.host,
                port = options.port,
                cause = error,
            )

            else -> TerminalFailure.SshChannelFailed(
                message = message,
                cause = error,
            )
        }
    }

    fun mapRuntimeFailure(error: Throwable): TerminalFailure.RuntimeTerminated {
        return TerminalFailure.RuntimeTerminated(
            identity = TerminalIdentity.Ssh,
            message = error.message,
            cause = error,
        )
    }

    private fun Throwable.isAuthenticationFailure(): Boolean {
        return message.orEmpty().contains("Auth fail", ignoreCase = true) ||
            message.orEmpty().contains("authentication", ignoreCase = true)
    }

    private fun Throwable.isHostKeyFailure(): Boolean {
        val text = message.orEmpty()
        return text.contains("reject HostKey", ignoreCase = true) ||
            text.contains("HostKey has been changed", ignoreCase = true) ||
            text.contains("UnknownHostKey", ignoreCase = true)
    }

    private fun Throwable.isConnectionFailure(): Boolean {
        return this is SocketTimeoutException ||
            this is ConnectException ||
            this is UnknownHostException ||
            this is NoRouteToHostException ||
            hasCause<SocketTimeoutException>() ||
            hasCause<ConnectException>() ||
            hasCause<UnknownHostException>() ||
            hasCause<NoRouteToHostException>() ||
            message.orEmpty().contains("timeout", ignoreCase = true) ||
            message.orEmpty().contains("Connection refused", ignoreCase = true) ||
            message.orEmpty().contains("UnknownHost", ignoreCase = true)
    }

    private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean {
        var current: Throwable? = cause
        while (current != null) {
            if (current is T) {
                return true
            }
            current = current.cause
        }
        return false
    }
}

internal class SshInvalidOpenOptionsException(
    val failure: TerminalFailure.InvalidOpenOptions,
) : IllegalArgumentException(failure.message)
