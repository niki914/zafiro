package com.niki914.libterm.runtime

import com.niki914.libterm.BackendAvailability
import com.niki914.libterm.PrivilegeProvider
import com.niki914.libterm.TerminalFailure
import com.niki914.libterm.TerminalIdentity
import com.niki914.libterm.runtime.internal.RuntimePrivilegeProvider
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class RuntimePrivilegeProviderTest {

    @Test
    fun `user delegates to libsu provider only`() = runTest {
        val libsu = RecordingPrivilegeProvider(BackendAvailability.Available)
        val shizuku = RecordingPrivilegeProvider(shizukuUnavailable())
        val provider = RuntimePrivilegeProvider(
            libsuProvider = libsu,
            shizukuProvider = shizuku,
        )

        val result = provider.getAvailability(TerminalIdentity.User)

        assertSame(BackendAvailability.Available, result)
        assertEquals(listOf(TerminalIdentity.User), libsu.calls)
        assertEquals(emptyList(), shizuku.calls)
    }

    @Test
    fun `root delegates to libsu provider only`() = runTest {
        val libsu = RecordingPrivilegeProvider(BackendAvailability.Available)
        val shizuku = RecordingPrivilegeProvider(shizukuUnavailable())
        val provider = RuntimePrivilegeProvider(
            libsuProvider = libsu,
            shizukuProvider = shizuku,
        )

        val result = provider.getAvailability(TerminalIdentity.Su)

        assertSame(BackendAvailability.Available, result)
        assertEquals(listOf(TerminalIdentity.Su), libsu.calls)
        assertEquals(emptyList(), shizuku.calls)
    }

    @Test
    fun `shizuku delegates to shizuku provider only`() = runTest {
        val libsu = RecordingPrivilegeProvider(shizukuUnavailable())
        val shizukuFailure = TerminalFailure.AuthorizationDenied(
            identity = TerminalIdentity.Shizuku,
            message = "permission denied",
        )
        val shizukuResult = BackendAvailability.Unauthorized(shizukuFailure)
        val shizuku = RecordingPrivilegeProvider(shizukuResult)
        val provider = RuntimePrivilegeProvider(
            libsuProvider = libsu,
            shizukuProvider = shizuku,
        )

        val result = provider.getAvailability(TerminalIdentity.Shizuku)

        assertSame(shizukuResult, result)
        assertEquals(emptyList(), libsu.calls)
        assertEquals(listOf(TerminalIdentity.Shizuku), shizuku.calls)
    }

    @Test
    fun `ssh is available without delegating to local providers`() = runTest {
        val libsu = RecordingPrivilegeProvider(shizukuUnavailable())
        val shizuku = RecordingPrivilegeProvider(shizukuUnavailable())
        val provider = RuntimePrivilegeProvider(
            libsuProvider = libsu,
            shizukuProvider = shizuku,
        )

        val result = provider.getAvailability(TerminalIdentity.Ssh)

        assertSame(BackendAvailability.Available, result)
        assertEquals(emptyList(), libsu.calls)
        assertEquals(emptyList(), shizuku.calls)
    }

    @Test
    fun `delegated failure object is returned unchanged`() = runTest {
        val failure = TerminalFailure.BackendUnavailable(
            identity = TerminalIdentity.Su,
            message = "root unavailable",
        )
        val unavailable = BackendAvailability.Unavailable(failure)
        val provider = RuntimePrivilegeProvider(
            libsuProvider = RecordingPrivilegeProvider(unavailable),
            shizukuProvider = RecordingPrivilegeProvider(shizukuUnavailable()),
        )

        val result = provider.getAvailability(TerminalIdentity.Su)

        assertSame(unavailable, result)
    }

    private class RecordingPrivilegeProvider(
        private val result: BackendAvailability,
    ) : PrivilegeProvider {
        val calls = mutableListOf<TerminalIdentity>()

        override suspend fun getAvailability(identity: TerminalIdentity): BackendAvailability {
            calls += identity
            return result
        }
    }

    private fun shizukuUnavailable(): BackendAvailability {
        return BackendAvailability.Unavailable(
            TerminalFailure.BackendUnavailable(
                identity = TerminalIdentity.Shizuku,
                message = "not used",
            ),
        )
    }
}
