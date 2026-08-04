package com.niki914.libterm.runtime.internal

import com.niki914.libterm.AuthorizationResult
import com.niki914.libterm.BackendAvailability
import com.niki914.libterm.PrivilegeAuthorizer
import com.niki914.libterm.PrivilegeProvider
import com.niki914.libterm.TerminalFailure
import com.niki914.libterm.TerminalIdentity
import com.niki914.libterm.backend.libsu.LibsuPrivilegeProvider
import com.niki914.libterm.backend.shizuku.ShizukuPrivilegeAuthorizer

internal class RuntimePrivilegeAuthorizer(
    private val libsuProvider: PrivilegeProvider = LibsuPrivilegeProvider(),
    private val shizukuAuthorizer: PrivilegeAuthorizer = ShizukuPrivilegeAuthorizer(),
) : PrivilegeAuthorizer {
    override suspend fun requestAuthorization(identity: TerminalIdentity): AuthorizationResult {
        return when (identity) {
            TerminalIdentity.User,
            TerminalIdentity.Su -> requestLibsuAuthorization(identity)

            TerminalIdentity.Shizuku -> shizukuAuthorizer.requestAuthorization(identity)

            TerminalIdentity.Ssh -> AuthorizationResult.Granted
        }
    }

    private suspend fun requestLibsuAuthorization(identity: TerminalIdentity): AuthorizationResult {
        return when (val availability = libsuProvider.getAvailability(identity)) {
            BackendAvailability.Available -> AuthorizationResult.Granted
            is BackendAvailability.Unauthorized -> availability.failure.toDeniedOrFailed(identity)
            is BackendAvailability.Unavailable -> availability.failure.toUnavailableOrFailed(
                identity
            )
        }
    }

    private fun TerminalFailure.toDeniedOrFailed(identity: TerminalIdentity): AuthorizationResult {
        return when (this) {
            is TerminalFailure.AuthorizationDenied -> AuthorizationResult.Denied(this)
            else -> AuthorizationResult.Failed(
                TerminalFailure.AuthorizationFailed(
                    identity = identity,
                    message = message,
                ),
            )
        }
    }

    private fun TerminalFailure.toUnavailableOrFailed(identity: TerminalIdentity): AuthorizationResult {
        return when (this) {
            is TerminalFailure.BackendUnavailable -> AuthorizationResult.Unavailable(this)
            else -> AuthorizationResult.Failed(
                TerminalFailure.AuthorizationFailed(
                    identity = identity,
                    message = message,
                ),
            )
        }
    }
}
