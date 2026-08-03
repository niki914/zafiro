package com.niki914.libterm.testing

import com.niki914.libterm.AuthorizationResult
import com.niki914.libterm.PrivilegeAuthorizer
import com.niki914.libterm.TerminalFailure
import com.niki914.libterm.TerminalIdentity

class FakeAuthorizer : PrivilegeAuthorizer {
    private val resultsByIdentity = mutableMapOf<TerminalIdentity, AuthorizationResult>()

    val requestedIdentities = mutableListOf<TerminalIdentity>()

    override suspend fun requestAuthorization(identity: TerminalIdentity): AuthorizationResult {
        requestedIdentities += identity
        return resultsByIdentity[identity] ?: AuthorizationResult.Granted
    }

    fun setGranted(identity: TerminalIdentity) {
        resultsByIdentity[identity] = AuthorizationResult.Granted
    }

    fun setDenied(identity: TerminalIdentity, message: String? = null) {
        resultsByIdentity[identity] = AuthorizationResult.Denied(
            TerminalFailure.AuthorizationDenied(identity = identity, message = message),
        )
    }

    fun setUnavailable(identity: TerminalIdentity, message: String? = null) {
        resultsByIdentity[identity] = AuthorizationResult.Unavailable(
            TerminalFailure.BackendUnavailable(identity = identity, message = message),
        )
    }

    fun setFailed(identity: TerminalIdentity, message: String? = null) {
        resultsByIdentity[identity] = AuthorizationResult.Failed(
            TerminalFailure.AuthorizationFailed(identity = identity, message = message),
        )
    }
}
