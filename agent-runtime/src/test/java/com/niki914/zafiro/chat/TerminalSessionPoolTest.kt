package com.niki914.zafiro.chat

import com.niki914.libterm.OpenResult
import com.niki914.libterm.SshAuth
import com.niki914.libterm.SshHostKeyPolicy
import com.niki914.libterm.SshOpenOptions
import com.niki914.libterm.TerminalBytes
import com.niki914.libterm.TerminalIdentity
import com.niki914.libterm.runtime.CommandResult
import com.niki914.libterm.runtime.TermResult
import com.niki914.zafiro.chat.agentic.shell.TerminalAsyncStartOutcome
import com.niki914.zafiro.chat.agentic.shell.TerminalCloseOutcome
import com.niki914.zafiro.chat.agentic.shell.TerminalCommandOutcome
import com.niki914.zafiro.chat.agentic.shell.TerminalOpenOutcome
import com.niki914.zafiro.chat.agentic.shell.TerminalReadOutcome
import com.niki914.zafiro.chat.agentic.shell.TerminalRuntimePort
import com.niki914.zafiro.chat.agentic.shell.TerminalSessionPool
import com.niki914.zafiro.chat.agentic.shell.TerminalSessionPort
import com.niki914.zafiro.chat.util.SilentLoggerRule
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TerminalSessionPoolTest {

    @get:Rule
    val silentLogger = SilentLoggerRule()

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
    fun readSessionReturnsSessionNotFoundWithoutOpeningRuntime() = runTest {
        val outcome = TerminalSessionPool.readSession(session = "user")

        assertEquals(TerminalReadOutcome.SessionNotFound("user"), outcome)
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
    fun closeAllClearsPendingNotifications() = runTest {
        val fakeRuntime = FakeTerminalRuntime()
        installFakeRuntime(fakeRuntime).use {
            installHandles("a001", "b002").use {
                TerminalSessionPool.open(identity = "user")
                TerminalSessionPool.startAsync(
                    session = "a001", command = "echo done", timeoutMs = 1_000L,
                    notifyOnComplete = true,
                )
                waitForAsyncCompletion("a001")
                Thread.sleep(20)
                assertTrue(TerminalSessionPool.drainPendingNotifications().isNotEmpty())

                // Start a second task and enqueue another notification
                TerminalSessionPool.open(identity = "user")
                TerminalSessionPool.startAsync(
                    session = "b002", command = "echo done", timeoutMs = 1_000L,
                    notifyOnComplete = true,
                )
                waitForAsyncCompletion("b002")
                Thread.sleep(20)

                TerminalSessionPool.closeAll()
                assertTrue(
                    "Notifications cleared by closeAll",
                    TerminalSessionPool.drainPendingNotifications().isEmpty()
                )
            }
        }
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

    // AC-12: After background command completes via invokeOnCompletion, the execution
    // Mutex is auto-released. Verified by starting a blocking command right after.
    @Test
    fun startAsync_invokeOnCompletionReleasesMutex_allowsExecuteBlocking() = runTest {
        val fakeRuntime = FakeTerminalRuntime()
        installFakeRuntime(fakeRuntime).use {
            installHandles("a001").use {
                TerminalSessionPool.open(identity = "user")
                TerminalSessionPool.startAsync(
                    session = "a001", command = "echo done", timeoutMs = 1_000L,
                )
                waitForAsyncCompletion("a001")

                var result: Any? = null
                repeat(100) {
                    result = TerminalSessionPool.executeBlocking(
                        session = "a001", command = "echo next", timeoutMs = 1_000L,
                    )
                    if (result is TerminalCommandOutcome.Success) return@repeat
                }
                assertTrue(
                    "Mutex released for blocking: $result",
                    result is TerminalCommandOutcome.Success
                )
            }
        }
    }

    /**
     * F-08: notify_on_complete=true enqueues a notification that survives drain.
     * After the async job completes, drainPendingNotifications returns the
     * notification and a second drain is empty.
     */
    @Test
    fun startAsync_notifyOnComplete_enqueuesAndDrainsNotification() = runTest {
        val fakeRuntime = FakeTerminalRuntime()
        installFakeRuntime(fakeRuntime).use {
            installHandles("a001").use {
                TerminalSessionPool.open(identity = "user")
                TerminalSessionPool.startAsync(
                    session = "a001", command = "echo done", timeoutMs = 1_000L,
                    notifyOnComplete = true,
                )
                waitForAsyncCompletion("a001")
                // The invokeOnCompletion callback runs on the IO dispatcher;
                // give it a chance to finish enqueuing the notification.
                Thread.sleep(20)

                val firstDrain = TerminalSessionPool.drainPendingNotifications()
                assertTrue("Notification enqueued", firstDrain.isNotEmpty())
                assertTrue(firstDrain.single().contains("[IMPORTANT: Background process a001"))

                val secondDrain = TerminalSessionPool.drainPendingNotifications()
                assertTrue("Second drain empty", secondDrain.isEmpty())
            }
        }
    }

    /**
     * readSession returns TimedOut (not Exited) when the background command
     * timed out.
     */
    @Test
    fun readSession_asyncCompletedWithTimeout_returnsTimedOut() = runTest {
        val fakeRuntime = FakeTerminalRuntime()
        installFakeRuntime(fakeRuntime).use {
            installHandles("a001").use {
                TerminalSessionPool.open(identity = "user")
                fakeRuntime.openedSessions.single().nextResult = commandResult(
                    stdout = "partial", timedOut = true,
                )
                TerminalSessionPool.startAsync(
                    session = "a001", command = "sleep 999", timeoutMs = 1_000L,
                )
                waitForAsyncCompletion("a001")

                val outcome = TerminalSessionPool.readSession("a001")
                assertTrue(outcome is TerminalReadOutcome.TimedOut)
                assertEquals("partial", (outcome as TerminalReadOutcome.TimedOut).output)
            }
        }
    }

    /**
     * readSession merges stdout + stderr into the output field for both
     * running and completed states.
     */
    @Test
    fun readSession_mergesStdoutAndStderr() = runTest {
        val fakeRuntime = FakeTerminalRuntime()
        installFakeRuntime(fakeRuntime).use {
            installHandles("a001").use {
                TerminalSessionPool.open(identity = "user")
                fakeRuntime.openedSessions.single().nextResult = commandResult(
                    stdout = "hello", stderr = "world",
                )
                TerminalSessionPool.startAsync(
                    session = "a001", command = "echo test", timeoutMs = 1_000L,
                )
                waitForAsyncCompletion("a001")

                val outcome = TerminalSessionPool.readSession("a001")
                assertTrue(outcome is TerminalReadOutcome.Exited)
                assertTrue((outcome as TerminalReadOutcome.Exited).output.contains("hello"))
                assertTrue(outcome.output.contains("world"))
            }
        }
    }

    /**
     * readSession returns a fixed elapsed_seconds after completion,
     * not a value that grows with wall-clock time after the task ends.
     */
    @Test
    fun readSession_elapsedSecondsStableAfterCompletion() = runTest {
        val fakeRuntime = FakeTerminalRuntime()
        installFakeRuntime(fakeRuntime).use {
            installHandles("a001").use {
                TerminalSessionPool.open(identity = "user")
                TerminalSessionPool.startAsync(
                    session = "a001", command = "echo done", timeoutMs = 1_000L,
                )
                waitForAsyncCompletion("a001")

                val first = TerminalSessionPool.readSession("a001") as TerminalReadOutcome.Exited
                val second = TerminalSessionPool.readSession("a001") as TerminalReadOutcome.Exited
                assertEquals(first.elapsedSeconds, second.elapsedSeconds)
            }
        }
    }

    /**
     * readSession returns Crashed when the background job throws an unexpected
     * exception inside exec(). The error message and elapsed_seconds are preserved.
     */
    @Test
    fun readSession_unexpectedErrorInExec_returnsCrashed() = runTest {
        val fakeRuntime = FakeTerminalRuntime()
        installFakeRuntime(fakeRuntime).use {
            installHandles("a001").use {
                TerminalSessionPool.open(identity = "user")
                fakeRuntime.openedSessions.single().throwOnExec = RuntimeException("boom")
                TerminalSessionPool.startAsync(
                    session = "a001", command = "echo test", timeoutMs = 1_000L,
                )
                waitForAsyncCompletion("a001")

                val outcome = TerminalSessionPool.readSession("a001")
                assertTrue(outcome is TerminalReadOutcome.Crashed)
                val crashed = outcome as TerminalReadOutcome.Crashed
                assertEquals("a001", crashed.session)
                assertTrue(crashed.errorMessage.contains("boom"))
                assertTrue(crashed.elapsedSeconds >= 0L)
            }
        }
    }

    /**
     * When notify_on_complete is set and the background job throws, the
     * completion notification includes the exception message.
     */
    @Test
    fun startAsync_unexpectedErrorWithNotifyOnComplete_enqueuesErrorNotification() = runTest {
        val fakeRuntime = FakeTerminalRuntime()
        installFakeRuntime(fakeRuntime).use {
            installHandles("a001").use {
                TerminalSessionPool.open(identity = "user")
                fakeRuntime.openedSessions.single().throwOnExec = RuntimeException("crash-boom")
                TerminalSessionPool.startAsync(
                    session = "a001", command = "echo test", timeoutMs = 1_000L,
                    notifyOnComplete = true,
                )
                waitForAsyncCompletion("a001")
                Thread.sleep(20)

                val notifications = TerminalSessionPool.drainPendingNotifications()
                assertTrue("Notification enqueued", notifications.isNotEmpty())
                assertTrue(notifications.single().contains("crash-boom"))
            }
        }
    }

    /**
     * Polls [TerminalSessionPool.readSession] until the async task exits, then
     * asserts the final outcome. The execJob runs on the pool's IO dispatcher so
     * [runTest] does not automatically advance it; polling bridges the gap.
     */
    private suspend fun waitForAsyncCompletion(session: String) {
        repeat(500) {
            when (val outcome = TerminalSessionPool.readSession(session)) {
                is TerminalReadOutcome.Exited -> return
                is TerminalReadOutcome.TimedOut -> return
                is TerminalReadOutcome.Crashed -> return
                is TerminalReadOutcome.Running -> { /* IO thread still working, retry */
                }

                else -> throw AssertionError("Unexpected read outcome: $outcome")
            }
        }
        throw AssertionError("Async task on $session did not complete within 500 polls")
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
        var throwOnExec: Throwable? = null

        override suspend fun exec(command: String, timeoutMillis: Long): TermResult<CommandResult> {
            commands.add(command)
            throwOnExec?.let { throw it }
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
