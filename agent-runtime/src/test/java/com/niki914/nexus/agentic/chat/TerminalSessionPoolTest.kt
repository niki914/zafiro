package com.niki914.nexus.agentic.chat

import com.niki914.libterm.OpenResult
import com.niki914.libterm.SshAuth
import com.niki914.libterm.SshHostKeyPolicy
import com.niki914.libterm.SshOpenOptions
import com.niki914.libterm.TerminalBytes
import com.niki914.libterm.TerminalIdentity
import com.niki914.libterm.runtime.CommandResult
import com.niki914.libterm.runtime.TermResult
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalAsyncReadOutcome
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalAsyncStartOutcome
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalCloseOutcome
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalCommandOutcome
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalOpenOutcome
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalRuntimePort
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalSessionPool
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalSessionPort
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalSessionPoolTest {
    @After
    fun tearDown() {
        runTest {
            TerminalSessionPool.closeAll()
        }
    }

    @Test
    fun openRejectsUnknownIdentityBeforeRuntimeInitialization() = runTest {
        val outcome = TerminalSessionPool.open(identity = "foobar")

        assertTrue(outcome is TerminalOpenOutcome.InvalidRequest)
        assertEquals(
            "Field 'identity' must be one of user, root, shizuku.",
            (outcome as TerminalOpenOutcome.InvalidRequest).message,
        )
    }

    @Test
    fun executeBlockingReturnsSessionNotFoundWithoutOpeningRuntime() = runTest {
        val outcome = TerminalSessionPool.executeBlocking(
            session = "user",
            command = "pwd",
            timeoutMs = 1_000L,
        )

        assertTrue(outcome is TerminalCommandOutcome.SessionNotFound)
    }

    @Test
    fun startAsyncReturnsSessionNotFoundWithoutOpeningRuntime() = runTest {
        val outcome = TerminalSessionPool.startAsync(
            session = "user",
            command = "sleep 10",
            timeoutMs = 1_000L,
        )

        assertEquals(TerminalAsyncStartOutcome.SessionNotFound("user"), outcome)
    }

    @Test
    fun readAsyncResultReturnsSessionNotFoundWithoutOpeningRuntime() = runTest {
        val outcome = TerminalSessionPool.readAsyncResult(session = "user", asyncId = "a1")

        assertEquals(TerminalAsyncReadOutcome.SessionNotFound("user"), outcome)
    }

    @Test
    fun closeIsIdempotentForMissingSession() = runTest {
        val outcome = TerminalSessionPool.close(session = "user")

        assertEquals(TerminalCloseOutcome.Closed, outcome)
    }

    @Test
    fun closeAllClearsMissingStateAndKeepsPoolReusable() = runTest {
        val first = TerminalSessionPool.closeAll()
        val second = TerminalSessionPool.closeAll()

        assertEquals(0, first.closedCount)
        assertEquals(0, second.closedCount)
        assertNull(TerminalSessionPool.get("user"))
    }

    @Test
    fun openCreatesDistinctShortHandlesForSameIdentity() = runTest {
        val fakeRuntime = FakeTerminalRuntime()
        installFakeRuntime(fakeRuntime).use {
            installHandles("a3f9", "b401").use {
                val first = TerminalSessionPool.open(identity = "user")
                val second = TerminalSessionPool.open(identity = "user")

                assertTrue(first is TerminalOpenOutcome.Success)
                assertTrue(second is TerminalOpenOutcome.Success)
                val firstSuccess = first as TerminalOpenOutcome.Success
                val secondSuccess = second as TerminalOpenOutcome.Success
                assertEquals("a3f9", firstSuccess.session)
                assertEquals("b401", secondSuccess.session)
                assertNotEquals(firstSuccess.session, secondSuccess.session)
                assertTrue(
                    TerminalSessionPool.publicHandleRegexForTest().matches(firstSuccess.session)
                )
                assertTrue(
                    TerminalSessionPool.publicHandleRegexForTest().matches(secondSuccess.session)
                )
                assertEquals("user", firstSuccess.identity)
                assertEquals("user", secondSuccess.identity)
                assertEquals(2, fakeRuntime.openedSessions.size)
            }
        }
    }

    @Test
    fun openRetriesWhenGeneratedHandleCollides() = runTest {
        val fakeRuntime = FakeTerminalRuntime()
        installFakeRuntime(fakeRuntime).use {
            installHandles("a3f9", "a3f9", "b401").use {
                val first = TerminalSessionPool.open(identity = "user")
                val second = TerminalSessionPool.open(identity = "root")

                assertEquals("a3f9", (first as TerminalOpenOutcome.Success).session)
                val secondSuccess = second as TerminalOpenOutcome.Success
                assertEquals("b401", secondSuccess.session)
                assertEquals("root", secondSuccess.identity)
                assertEquals(2, fakeRuntime.openedSessions.size)
            }
        }
    }

    @Test
    fun openMapsShizukuIdentityIntoRuntime() = runTest {
        val fakeRuntime = FakeTerminalRuntime()
        installFakeRuntime(fakeRuntime).use {
            installHandles("a3f9").use {
                val outcome = TerminalSessionPool.open(identity = "shizuku")

                val success = outcome as TerminalOpenOutcome.Success
                assertEquals("a3f9", success.session)
                assertEquals("shizuku", success.identity)
                assertEquals(listOf(TerminalIdentity.Shizuku), fakeRuntime.openedIdentities)
            }
        }
    }

    @Test
    fun openSshUsesSshIdentityAndOptions() = runTest {
        val fakeRuntime = FakeTerminalRuntime()
        installFakeRuntime(fakeRuntime).use {
            installHandles("a3f9").use {
                val outcome = TerminalSessionPool.openSsh(
                    options = SshOpenOptions(
                        host = "example.com",
                        port = 2222,
                        username = "alice",
                        auth = SshAuth.Password("secret"),
                        hostKeyPolicy = SshHostKeyPolicy.KnownHostsFile(
                            path = "/data/local/tmp/known_hosts",
                            strict = false,
                        ),
                        connectTimeoutMillis = 1234,
                        serverAliveIntervalMillis = 5678,
                    ),
                    cwd = "/tmp",
                )

                val success = outcome as TerminalOpenOutcome.Success
                assertEquals("a3f9", success.session)
                assertEquals("ssh", success.identity)
                assertEquals(listOf(TerminalIdentity.Ssh), fakeRuntime.openedIdentities)
                assertEquals(
                    SshOpenOptions(
                        host = "example.com",
                        port = 2222,
                        username = "alice",
                        auth = SshAuth.Password("secret"),
                        hostKeyPolicy = SshHostKeyPolicy.KnownHostsFile(
                            path = "/data/local/tmp/known_hosts",
                            strict = false,
                        ),
                        connectTimeoutMillis = 1234,
                        serverAliveIntervalMillis = 5678,
                    ),
                    fakeRuntime.openedSshOptions.single(),
                )
                assertEquals(listOf("/tmp"), fakeRuntime.openedCwds)
            }
        }
    }

    @Test
    fun executeBlockingKeepsSessionsIsolatedByHandle() = runTest {
        val fakeRuntime = FakeTerminalRuntime()
        installFakeRuntime(fakeRuntime).use {
            installHandles("a3f9", "b401").use {
                TerminalSessionPool.open(identity = "user")
                TerminalSessionPool.open(identity = "user")
                fakeRuntime.openedSessions[0].nextResult = commandResult(stdout = "first")
                fakeRuntime.openedSessions[1].nextResult = commandResult(stdout = "second")

                val first = TerminalSessionPool.executeBlocking(
                    session = "a3f9",
                    command = "echo first",
                    timeoutMs = 1_000L,
                )
                val second = TerminalSessionPool.executeBlocking(
                    session = "b401",
                    command = "echo second",
                    timeoutMs = 1_000L,
                )

                val firstSuccess = first as TerminalCommandOutcome.Success
                val secondSuccess = second as TerminalCommandOutcome.Success
                assertEquals("a3f9", firstSuccess.session)
                assertEquals("b401", secondSuccess.session)
                assertEquals("first", firstSuccess.result.stdoutText())
                assertEquals("second", secondSuccess.result.stdoutText())
                assertEquals(listOf("echo first"), fakeRuntime.openedSessions[0].commands)
                assertEquals(listOf("echo second"), fakeRuntime.openedSessions[1].commands)
            }
        }
    }

    @Test
    fun closeRemovesOnlyRequestedHandle() = runTest {
        val fakeRuntime = FakeTerminalRuntime()
        installFakeRuntime(fakeRuntime).use {
            installHandles("a3f9", "b401").use {
                TerminalSessionPool.open(identity = "user")
                TerminalSessionPool.open(identity = "user")

                val closeOutcome = TerminalSessionPool.close(session = "a3f9")
                val closedExec = TerminalSessionPool.executeBlocking(
                    session = "a3f9",
                    command = "pwd",
                    timeoutMs = 1_000L,
                )
                val remainingExec = TerminalSessionPool.executeBlocking(
                    session = "b401",
                    command = "pwd",
                    timeoutMs = 1_000L,
                )

                assertEquals(TerminalCloseOutcome.Closed, closeOutcome)
                assertEquals(TerminalCommandOutcome.SessionNotFound("a3f9"), closedExec)
                assertTrue(remainingExec is TerminalCommandOutcome.Success)
                assertTrue(fakeRuntime.openedSessions[0].closed)
                assertTrue(!fakeRuntime.openedSessions[1].closed)
            }
        }
    }

    @Test
    fun closeAllClearsAllGeneratedHandles() = runTest {
        val fakeRuntime = FakeTerminalRuntime()
        installFakeRuntime(fakeRuntime).use {
            installHandles("a3f9", "b401").use {
                TerminalSessionPool.open(identity = "user")
                TerminalSessionPool.open(identity = "root")

                val outcome = TerminalSessionPool.closeAll()
                val first = TerminalSessionPool.executeBlocking(
                    session = "a3f9",
                    command = "pwd",
                    timeoutMs = 1_000L,
                )
                val second = TerminalSessionPool.executeBlocking(
                    session = "b401",
                    command = "pwd",
                    timeoutMs = 1_000L,
                )

                assertEquals(2, outcome.closedCount)
                assertEquals(TerminalCommandOutcome.SessionNotFound("a3f9"), first)
                assertEquals(TerminalCommandOutcome.SessionNotFound("b401"), second)
                assertEquals(1, fakeRuntime.closeAllCount)
            }
        }
    }

    private fun installFakeRuntime(fakeRuntime: FakeTerminalRuntime): AutoCloseable {
        return TerminalSessionPool.installRuntimePortFactoryForTest { fakeRuntime }
    }

    private fun installHandles(vararg handles: String): AutoCloseable {
        val iterator = handles.iterator()
        return TerminalSessionPool.installHandleGeneratorForTest {
            check(iterator.hasNext()) { "No fake terminal handles left." }
            iterator.next()
        }
    }

    private class FakeTerminalRuntime : TerminalRuntimePort {
        val openedSessions = mutableListOf<FakeTerminalSession>()
        val openedIdentities = mutableListOf<TerminalIdentity>()
        val openedSshOptions = mutableListOf<SshOpenOptions?>()
        val openedCwds = mutableListOf<String?>()
        var closeAllCount = 0

        override suspend fun open(
            identity: TerminalIdentity,
            cwd: String?,
            sshOptions: SshOpenOptions?,
        ): OpenResult<TerminalSessionPort> {
            openedIdentities.add(identity)
            openedSshOptions.add(sshOptions)
            openedCwds.add(cwd)
            val session = FakeTerminalSession(id = "runtime-${openedSessions.size + 1}")
            openedSessions.add(session)
            return OpenResult.Success(session)
        }

        override suspend fun close(sessionId: String) = Unit

        override suspend fun closeAll(): Int {
            closeAllCount++
            openedSessions.forEach { it.closed = true }
            return openedSessions.size
        }
    }

    private class FakeTerminalSession(
        override val id: String,
    ) : TerminalSessionPort {
        override val stream = emptyFlow<com.niki914.libterm.runtime.TerminalTextChunk>()
        val commands = mutableListOf<String>()
        var nextResult: CommandResult = commandResult()
        var closed = false

        override suspend fun exec(command: String, timeoutMillis: Long): TermResult<CommandResult> {
            commands.add(command)
            return TermResult.Success(nextResult)
        }

        override suspend fun write(text: String) = Unit

        override suspend fun close() {
            closed = true
        }
    }

    private companion object {
        fun commandResult(
            stdout: String = "",
            stderr: String = "",
            exitCode: Int? = 0,
            timedOut: Boolean = false,
        ): CommandResult {
            return CommandResult(
                command = "cmd",
                stdout = TerminalBytes.of(stdout.encodeToByteArray()),
                stderr = TerminalBytes.of(stderr.encodeToByteArray()),
                exitCode = exitCode,
                timedOut = timedOut,
            )
        }

        fun CommandResult.stdoutText(): String = stdout.toByteArray().decodeToString()
    }
}
