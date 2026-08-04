package com.niki914.libterm

sealed interface TerminalFailure {
    val identity: TerminalIdentity?
    val message: String?

    data class BackendUnavailable(
        override val identity: TerminalIdentity,
        override val message: String? = null,
    ) : TerminalFailure

    data class AuthorizationDenied(
        override val identity: TerminalIdentity,
        override val message: String? = null,
    ) : TerminalFailure

    data class AuthorizationFailed(
        override val identity: TerminalIdentity,
        override val message: String? = null,
        val cause: Throwable? = null,
    ) : TerminalFailure

    data class StartupFailed(
        override val identity: TerminalIdentity,
        override val message: String? = null,
        val cause: Throwable? = null,
    ) : TerminalFailure

    data class InvalidOpenOptions(
        override val identity: TerminalIdentity? = null,
        override val message: String? = null,
    ) : TerminalFailure

    data class SshConnectionFailed(
        override val identity: TerminalIdentity = TerminalIdentity.Ssh,
        override val message: String? = null,
        val host: String? = null,
        val port: Int? = null,
        val cause: Throwable? = null,
    ) : TerminalFailure

    data class SshAuthenticationFailed(
        override val identity: TerminalIdentity = TerminalIdentity.Ssh,
        override val message: String? = null,
        val username: String? = null,
        val cause: Throwable? = null,
    ) : TerminalFailure

    data class SshHostKeyVerificationFailed(
        override val identity: TerminalIdentity = TerminalIdentity.Ssh,
        override val message: String? = null,
        val host: String? = null,
        val cause: Throwable? = null,
    ) : TerminalFailure

    data class SshChannelFailed(
        override val identity: TerminalIdentity = TerminalIdentity.Ssh,
        override val message: String? = null,
        val cause: Throwable? = null,
    ) : TerminalFailure

    data class RuntimeTerminated(
        override val identity: TerminalIdentity,
        override val message: String? = null,
        val cause: Throwable? = null,
    ) : TerminalFailure

    data class AlreadyClosed(
        override val identity: TerminalIdentity? = null,
        override val message: String? = null,
    ) : TerminalFailure
}
