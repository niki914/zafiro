package com.niki914.libterm.backend.shizuku

import com.niki914.libterm.BackendAvailability
import com.niki914.libterm.TerminalFailure
import com.niki914.libterm.TerminalIdentity
import com.niki914.libterm.backend.shizuku.internal.ShizukuAccessChecker
import com.niki914.libterm.backend.shizuku.internal.ShizukuAccessState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ShizukuPrivilegeProviderTest {

    @Test
    fun `not running maps to backend unavailable`() = runTest {
        val provider = ShizukuPrivilegeProvider(
            FakeShizukuAccessChecker(ShizukuAccessState.NotInstalledOrNotRunning),
        )

        val result = provider.getAvailability(TerminalIdentity.Shizuku)

        val unavailable = assertIs<BackendAvailability.Unavailable>(result)
        val failure = assertIs<TerminalFailure.BackendUnavailable>(unavailable.failure)
        assertEquals(TerminalIdentity.Shizuku, failure.identity)
        assertEquals("Shizuku is not installed or not running", failure.message)
    }

    @Test
    fun `unauthorized maps to authorization denied`() = runTest {
        val provider = ShizukuPrivilegeProvider(
            FakeShizukuAccessChecker(ShizukuAccessState.Unauthorized),
        )

        val result = provider.getAvailability(TerminalIdentity.Shizuku)

        val unauthorized = assertIs<BackendAvailability.Unauthorized>(result)
        val failure = assertIs<TerminalFailure.AuthorizationDenied>(unauthorized.failure)
        assertEquals(TerminalIdentity.Shizuku, failure.identity)
        assertEquals("Shizuku authorization was denied", failure.message)
    }

    @Test
    fun `authorized maps to available`() = runTest {
        val checker = FakeShizukuAccessChecker(ShizukuAccessState.Authorized)
        val provider = ShizukuPrivilegeProvider(checker)

        val result = provider.getAvailability(TerminalIdentity.Shizuku)

        assertEquals(BackendAvailability.Available, result)
        assertEquals(1, checker.checkCallCount)
    }

    @Test
    fun `user maps to unsupported backend unavailable`() = runTest {
        val checker = FakeShizukuAccessChecker(ShizukuAccessState.Authorized)
        val provider = ShizukuPrivilegeProvider(checker)

        val result = provider.getAvailability(TerminalIdentity.User)

        val unavailable = assertIs<BackendAvailability.Unavailable>(result)
        val failure = assertIs<TerminalFailure.BackendUnavailable>(unavailable.failure)
        assertEquals(TerminalIdentity.User, failure.identity)
        assertEquals("Shizuku backend only supports SHIZUKU", failure.message)
        assertEquals(0, checker.checkCallCount)
    }

    @Test
    fun `root maps to unsupported backend unavailable`() = runTest {
        val checker = FakeShizukuAccessChecker(ShizukuAccessState.Authorized)
        val provider = ShizukuPrivilegeProvider(checker)

        val result = provider.getAvailability(TerminalIdentity.Su)

        val unavailable = assertIs<BackendAvailability.Unavailable>(result)
        val failure = assertIs<TerminalFailure.BackendUnavailable>(unavailable.failure)
        assertEquals(TerminalIdentity.Su, failure.identity)
        assertEquals("Shizuku backend only supports SHIZUKU", failure.message)
        assertEquals(0, checker.checkCallCount)
    }

    private class FakeShizukuAccessChecker(
        private val state: ShizukuAccessState,
    ) : ShizukuAccessChecker {
        var checkCallCount: Int = 0
            private set

        override suspend fun checkAccess(): ShizukuAccessState {
            checkCallCount += 1
            return state
        }
    }
}
