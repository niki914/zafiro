package com.niki914.libterm.runtime

import com.niki914.libterm.AuthorizationResult
import com.niki914.libterm.BackendAvailability
import com.niki914.libterm.PrivilegeAuthorizer
import com.niki914.libterm.PrivilegeProvider
import com.niki914.libterm.TerminalFailure
import com.niki914.libterm.TerminalIdentity
import com.niki914.libterm.runtime.internal.RuntimePrivilegeAuthorizer
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class RuntimePrivilegeAuthorizerTest {

    @Test
    fun `root authorization denied is preserved and does not call shizuku authorizer`() = runTest {
        val suFailure = TerminalFailure.AuthorizationDenied(
            identity = TerminalIdentity.Su,
            message = "root denied",
        )
        val libsuProvider = RecordingPrivilegeProvider(
            BackendAvailability.Unauthorized(suFailure),
        )
        val shizukuAuthorizer = RecordingAuthorizer(AuthorizationResult.Granted)
        val authorizer = RuntimePrivilegeAuthorizer(
            libsuProvider = libsuProvider,
            shizukuAuthorizer = shizukuAuthorizer,
        )

        val result = authorizer.requestAuthorization(TerminalIdentity.Su)

        val denied = result as AuthorizationResult.Denied
        assertSame(suFailure, denied.failure)
        assertEquals(listOf(TerminalIdentity.Su), libsuProvider.calls)
        assertEquals(emptyList(), shizukuAuthorizer.calls)
    }

    @Test
    fun `shizuku authorization delegates to shizuku authorizer only`() = runTest {
        val shizukuFailure = TerminalFailure.AuthorizationDenied(
            identity = TerminalIdentity.Shizuku,
            message = "shizuku denied",
        )
        val shizukuResult = AuthorizationResult.Denied(shizukuFailure)
        val libsuProvider = RecordingPrivilegeProvider(BackendAvailability.Available)
        val shizukuAuthorizer = RecordingAuthorizer(shizukuResult)
        val authorizer = RuntimePrivilegeAuthorizer(
            libsuProvider = libsuProvider,
            shizukuAuthorizer = shizukuAuthorizer,
        )

        val result = authorizer.requestAuthorization(TerminalIdentity.Shizuku)

        assertSame(shizukuResult, result)
        assertEquals(emptyList(), libsuProvider.calls)
        assertEquals(listOf(TerminalIdentity.Shizuku), shizukuAuthorizer.calls)
    }

    private class RecordingPrivilegeProvider(
        private val availability: BackendAvailability,
    ) : PrivilegeProvider {
        val calls = mutableListOf<TerminalIdentity>()

        override suspend fun getAvailability(identity: TerminalIdentity): BackendAvailability {
            calls += identity
            return availability
        }
    }

    private class RecordingAuthorizer(
        private val result: AuthorizationResult,
    ) : PrivilegeAuthorizer {
        val calls = mutableListOf<TerminalIdentity>()

        override suspend fun requestAuthorization(identity: TerminalIdentity): AuthorizationResult {
            calls += identity
            return result
        }
    }
}
