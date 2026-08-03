package com.niki914.libterm.testing

import com.niki914.libterm.BackendAvailability
import com.niki914.libterm.PrivilegeProvider
import com.niki914.libterm.TerminalFailure
import com.niki914.libterm.TerminalIdentity

class FakeProvider : PrivilegeProvider {
    private val availabilityByIdentity = mutableMapOf<TerminalIdentity, BackendAvailability>()

    override suspend fun getAvailability(identity: TerminalIdentity): BackendAvailability {
        return availabilityByIdentity[identity] ?: BackendAvailability.Available
    }

    fun setAvailability(identity: TerminalIdentity, availability: BackendAvailability) {
        availabilityByIdentity[identity] = availability
    }

    fun setAvailable(identity: TerminalIdentity) {
        availabilityByIdentity[identity] = BackendAvailability.Available
    }

    fun setUnavailable(identity: TerminalIdentity, message: String? = null) {
        availabilityByIdentity[identity] = BackendAvailability.Unavailable(
            TerminalFailure.BackendUnavailable(identity = identity, message = message),
        )
    }

    fun setUnauthorized(identity: TerminalIdentity, message: String? = null) {
        availabilityByIdentity[identity] = BackendAvailability.Unauthorized(
            TerminalFailure.AuthorizationDenied(identity = identity, message = message),
        )
    }
}
