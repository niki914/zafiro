package com.niki914.libterm.backend.libsu

import com.niki914.libterm.BackendAvailability
import com.niki914.libterm.TerminalFailure
import com.niki914.libterm.TerminalIdentity
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LibsuPrivilegeProviderTest {

    @Test
    fun `user is always available`() = runTest {
        val checker = FakeRootAccessChecker(LibsuRootAccessResult.Unavailable("no root"))
        val provider = LibsuPrivilegeProvider(checker)

        val result = provider.getAvailability(TerminalIdentity.User)

        assertEquals(BackendAvailability.Available, result)
        assertEquals(0, checker.checkCallCount)
    }

    @Test
    fun `root unavailable maps to backend unavailable`() = runTest {
        val provider = LibsuPrivilegeProvider(
            FakeRootAccessChecker(LibsuRootAccessResult.Unavailable("root missing")),
        )

        val result = provider.getAvailability(TerminalIdentity.Su)

        val unavailable = assertIs<BackendAvailability.Unavailable>(result)
        val failure = assertIs<TerminalFailure.BackendUnavailable>(unavailable.failure)
        assertEquals(TerminalIdentity.Su, failure.identity)
        assertEquals("root missing", failure.message)
    }

    @Test
    fun `root unauthorized maps to authorization denied`() = runTest {
        val provider = LibsuPrivilegeProvider(
            FakeRootAccessChecker(LibsuRootAccessResult.Unauthorized("user denied")),
        )

        val result = provider.getAvailability(TerminalIdentity.Su)

        val unauthorized = assertIs<BackendAvailability.Unauthorized>(result)
        val failure = assertIs<TerminalFailure.AuthorizationDenied>(unauthorized.failure)
        assertEquals(TerminalIdentity.Su, failure.identity)
        assertEquals("user denied", failure.message)
    }

    @Test
    fun `root available maps to available`() = runTest {
        val checker = FakeRootAccessChecker(LibsuRootAccessResult.Available)
        val provider = LibsuPrivilegeProvider(checker)

        val result = provider.getAvailability(TerminalIdentity.Su)

        assertEquals(BackendAvailability.Available, result)
        assertEquals(1, checker.checkCallCount)
    }

    @Test
    fun `shizuku is unsupported by libsu provider`() = runTest {
        val checker = FakeRootAccessChecker(LibsuRootAccessResult.Available)
        val provider = LibsuPrivilegeProvider(checker)

        val result = provider.getAvailability(TerminalIdentity.Shizuku)

        val unavailable = assertIs<BackendAvailability.Unavailable>(result)
        val failure = assertIs<TerminalFailure.BackendUnavailable>(unavailable.failure)
        assertEquals(TerminalIdentity.Shizuku, failure.identity)
        assertEquals("libsu backend does not support SHIZUKU", failure.message)
        assertEquals(0, checker.checkCallCount)
    }

    private class FakeRootAccessChecker(
        private val result: LibsuRootAccessResult,
    ) : LibsuRootAccessChecker {
        var checkCallCount: Int = 0
            private set

        override suspend fun checkRootAccess(): LibsuRootAccessResult {
            checkCallCount += 1
            return result
        }
    }
}
