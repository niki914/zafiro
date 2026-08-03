package com.niki914.libterm

import com.niki914.libterm.testing.FakeBackend
import com.niki914.libterm.testing.FakeClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalSessionTest {

    @Test
    fun `start success moves session to running`() = runTest {
        val backend = FakeBackend(identity = TerminalIdentity.User)
        val session = createSession(
            backend = backend,
            clock = FakeClock(),
            scheduler = testScheduler,
        )

        val state = session.start()

        assertEquals(SessionState.Running, state)
        assertEquals(SessionState.Running, session.currentState)
        assertEquals("session-test", session.id)
        assertEquals(1, backend.startCallCount)

        backend.finishNormally()
    }

    @Test
    fun `start failure moves session to failed`() = runTest {
        val backend = FakeBackend(identity = TerminalIdentity.Su)
        val failure = TerminalFailure.StartupFailed(
            identity = TerminalIdentity.Su,
            message = "boom",
        )
        backend.failOnStart(failure)
        val session = createSession(
            backend = backend,
            clock = FakeClock(),
            scheduler = testScheduler,
        )

        val state = session.start()

        val failed = assertIs<SessionState.Failed>(state)
        assertEquals(failure, failed.failure)
        assertEquals(failed, session.currentState)
    }

    @Test
    fun `latest trims old chunks by chunk count and byte count`() = runTest {
        val backend = FakeBackend(identity = TerminalIdentity.User)
        val session = createSession(
            backend = backend,
            clock = FakeClock(),
            bufferConfig = TerminalBufferConfig(
                maxChunkCount = 3,
                maxByteCount = 4,
            ),
            scheduler = testScheduler,
        )

        session.start()
        runCurrent()
        backend.emitStdout(bytesOf("a"))
        backend.emitStdout(bytesOf("b"))
        backend.emitStdout(bytesOf("c"))
        backend.emitStdout(bytesOf("wxyz"))
        advanceUntilIdle()

        assertEquals(
            listOf(
                OutputChunk(
                    stream = OutputStream.STDOUT,
                    bytes = bytesOf("wxyz"),
                    timestampMillis = 0L
                ),
            ),
            session.latest(limit = 10),
        )

        backend.finishNormally()
    }

    @Test
    fun `stderr chunks stay marked after buffering`() = runTest {
        val clock = FakeClock(initialMillis = 10L)
        val backend = FakeBackend(identity = TerminalIdentity.User, clock = clock)
        val session = createSession(
            backend = backend,
            clock = clock,
            scheduler = testScheduler,
        )

        session.start()
        runCurrent()
        backend.emitStdout(bytesOf("out"))
        clock.advanceBy(5L)
        backend.emitStderr(bytesOf("err"))
        advanceUntilIdle()

        val latest = session.latest(limit = 2)
        assertEquals(2, latest.size)
        assertEquals(
            OutputChunk(
                stream = OutputStream.STDOUT,
                bytes = bytesOf("out"),
                timestampMillis = 10L
            ), latest[0]
        )
        assertEquals(
            OutputChunk(
                stream = OutputStream.STDERR,
                bytes = bytesOf("err"),
                timestampMillis = 15L
            ), latest[1]
        )

        backend.finishNormally()
    }

    @Test
    fun `send forwards TerminalBytes`() = runTest {
        val backend = FakeBackend(identity = TerminalIdentity.User)
        val session = createSession(
            backend = backend,
            clock = FakeClock(),
            scheduler = testScheduler,
        )
        val input = bytesOf("id\n")

        assertEquals(SessionState.Running, session.start())

        assertEquals(SendResult.Sent, session.send(input))
        assertEquals(listOf(input), backend.writes)

        backend.finishNormally()
    }

    @Test
    fun `send byte array convenience wraps copy`() = runTest {
        val backend = FakeBackend(identity = TerminalIdentity.User)
        val session = createSession(
            backend = backend,
            clock = FakeClock(),
            scheduler = testScheduler,
        )
        val input = byteArrayOf(1, 2, 3)

        assertEquals(SessionState.Running, session.start())
        assertEquals(SendResult.Sent, session.send(input))
        input[0] = 9

        assertEquals(listOf(TerminalBytes.of(byteArrayOf(1, 2, 3))), backend.writes)

        backend.finishNormally()
    }

    @Test
    fun `send after close returns already closed failure`() = runTest {
        val backend = FakeBackend(identity = TerminalIdentity.Shizuku)
        val session = createSession(
            backend = backend,
            clock = FakeClock(),
            scheduler = testScheduler,
        )

        session.start()
        val closeResult = backgroundScope.async { session.close() }
        runCurrent()
        backend.finishNormally()
        advanceUntilIdle()

        assertEquals(SessionState.Closed, closeResult.await())
        assertEquals(SessionState.Closed, session.currentState)
        assertEquals(1, backend.closeCallCount)

        val failed = assertIs<SendResult.Failed>(session.send(bytesOf("id\n")))
        val failure = assertIs<TerminalFailure.AlreadyClosed>(failed.failure)
        assertEquals(TerminalIdentity.Shizuku, failure.identity)
        assertEquals("Session is not running", failure.message)
        assertTrue(backend.writes.isEmpty())
    }

    @Test
    fun `send backend exception returns runtime terminated failure`() = runTest {
        val backend = ThrowingSendBackend(identity = TerminalIdentity.Su)
        val session = createSession(
            backend = backend,
            clock = FakeClock(),
            scheduler = testScheduler,
        )

        assertEquals(SessionState.Running, session.start())

        val failed = assertIs<SendResult.Failed>(session.send(bytesOf("id\n")))
        val failure = assertIs<TerminalFailure.RuntimeTerminated>(failed.failure)
        assertEquals(TerminalIdentity.Su, failure.identity)
        assertEquals("send failed", failure.message)
        assertEquals(SessionState.Failed(failure), session.currentState)

        backend.finishNormally()
    }

    @Test
    fun `send backend failed result moves session to failed`() = runTest {
        val failure = TerminalFailure.RuntimeTerminated(
            identity = TerminalIdentity.Su,
            message = "write failed",
        )
        val backend = FailedSendBackend(
            identity = TerminalIdentity.Su,
            failure = failure,
        )
        val session = createSession(
            backend = backend,
            clock = FakeClock(),
            scheduler = testScheduler,
        )

        assertEquals(SessionState.Running, session.start())

        assertEquals(SendResult.Failed(failure), session.send(bytesOf("id\n")))
        assertEquals(SessionState.Failed(failure), session.currentState)

        backend.finishNormally()
    }

    @Test
    fun `close before start is a no-op and does not block later start`() = runTest {
        val backend = FakeBackend(identity = TerminalIdentity.User)
        val session = createSession(
            backend = backend,
            clock = FakeClock(),
            scheduler = testScheduler,
        )

        assertEquals(SessionState.Closed, session.close())
        assertEquals(SessionState.Closed, session.currentState)
        assertEquals(0, backend.closeCallCount)

        val started = session.start()
        assertEquals(SessionState.Running, started)
        assertEquals(1, backend.startCallCount)

        backend.finishNormally()
    }

    @Test
    fun `await exit failure moves session to failed`() = runTest {
        val backend = FakeBackend(identity = TerminalIdentity.Su)
        val session = createSession(
            backend = backend,
            clock = FakeClock(),
            scheduler = testScheduler,
        )
        val failure = TerminalFailure.RuntimeTerminated(
            identity = TerminalIdentity.Su,
            message = "pty died",
        )

        session.start()
        runCurrent()
        backend.terminateWithFailure(failure)
        advanceUntilIdle()

        val failed = assertIs<SessionState.Failed>(session.currentState)
        assertEquals(failure, failed.failure)
    }

    @Test
    fun `close requested while start is pending never transitions back to running`() = runTest {
        val backend = StartControlledBackend(identity = TerminalIdentity.User)
        val session = createSession(
            backend = backend,
            clock = FakeClock(),
            scheduler = testScheduler,
        )

        val startResult = backgroundScope.async { session.start() }
        runCurrent()
        assertEquals(SessionState.Starting, session.currentState)

        val closeResult = backgroundScope.async { session.close() }
        runCurrent()
        assertEquals(0, backend.closeCallCount)

        backend.completeStart(BackendStartResult.Started)
        runCurrent()
        assertEquals(SessionState.Starting, session.currentState)
        assertEquals(1, backend.closeCallCount)

        backend.completeExit(null)
        advanceUntilIdle()

        assertEquals(SessionState.Closed, startResult.await())
        assertEquals(SessionState.Closed, closeResult.await())
        assertEquals(SessionState.Closed, session.currentState)
    }

    @Test
    fun `close maps await exit exception to runtime terminated failure`() = runTest {
        val backend = ThrowingExitBackend(identity = TerminalIdentity.Su)
        val session = createSession(
            backend = backend,
            clock = FakeClock(),
            scheduler = testScheduler,
        )

        assertEquals(SessionState.Running, session.start())

        val failed = assertIs<SessionState.Failed>(session.close())
        val failure = assertIs<TerminalFailure.RuntimeTerminated>(failed.failure)
        assertEquals(TerminalIdentity.Su, failure.identity)
        assertEquals("await exit failed", failure.message)
        assertEquals(SessionState.Failed(failure), session.currentState)
    }

    @Test
    fun `output flow exception moves session to failed`() = runTest {
        val backend = ThrowingOutputBackend(identity = TerminalIdentity.User)
        val session = createSession(
            backend = backend,
            clock = FakeClock(),
            scheduler = testScheduler,
        )

        assertEquals(SessionState.Running, session.start())

        backend.failOutputCollection()
        advanceUntilIdle()

        val failed = assertIs<SessionState.Failed>(session.currentState)
        val failure = assertIs<TerminalFailure.RuntimeTerminated>(failed.failure)
        assertEquals(TerminalIdentity.User, failure.identity)
        assertEquals("output failed", failure.message)
    }


    @Test
    fun `concurrent output keeps deterministic buffer invariants`() = runTest {
        val backend = FakeBackend(identity = TerminalIdentity.User)
        val session = createSession(
            backend = backend,
            clock = FakeClock(),
            bufferConfig = TerminalBufferConfig(
                maxChunkCount = 5,
                maxByteCount = 5,
            ),
            scheduler = testScheduler,
        )
        val emitted = ('a'..'t').map { bytesOf(it.toString()) }.toSet()

        session.start()
        runCurrent()
        ('a'..'t').forEach { value ->
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                backend.emitStdout(bytesOf(value.toString()))
            }
        }
        advanceUntilIdle()

        val latest = session.latest(limit = 100)
        assertEquals(5, latest.size)
        assertEquals(5, latest.sumOf { it.bytes.size })
        assertTrue(latest.all { it.stream == OutputStream.STDOUT })
        assertTrue(latest.all { it.bytes in emitted })
        assertEquals(latest.map { it.bytes }.toSet().size, latest.size)

        backend.finishNormally()
    }

    private fun createSession(
        backend: TerminalBackend,
        clock: FakeClock,
        bufferConfig: TerminalBufferConfig = TerminalBufferConfig(),
        scheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
    ): TerminalSession {
        return TerminalSession(
            id = "session-test",
            backend = backend,
            clock = clock,
            bufferConfig = bufferConfig,
            scope = CoroutineScope(UnconfinedTestDispatcher(scheduler)),
        )
    }

    private class StartControlledBackend(
        override val identity: TerminalIdentity,
    ) : TerminalBackend {
        private val startResult = CompletableDeferred<BackendStartResult>()
        private val exitResult = CompletableDeferred<TerminalFailure?>()

        override val output: Flow<OutputChunk> = emptyFlow()

        var closeCallCount: Int = 0
            private set

        override suspend fun start(openOptions: TerminalOpenOptions): BackendStartResult = startResult.await()

        override suspend fun send(input: TerminalBytes): SendResult = SendResult.Sent

        override suspend fun close() {
            closeCallCount += 1
        }

        override suspend fun awaitExit(): TerminalFailure? = exitResult.await()

        fun completeStart(result: BackendStartResult) {
            if (!startResult.isCompleted) {
                startResult.complete(result)
            }
        }

        fun completeExit(result: TerminalFailure?) {
            if (!exitResult.isCompleted) {
                exitResult.complete(result)
            }
        }
    }

    private class ThrowingExitBackend(
        override val identity: TerminalIdentity,
    ) : TerminalBackend {
        override val output: Flow<OutputChunk> = emptyFlow()

        override suspend fun start(openOptions: TerminalOpenOptions): BackendStartResult = BackendStartResult.Started

        override suspend fun send(input: TerminalBytes): SendResult = SendResult.Sent

        override suspend fun close() = Unit

        override suspend fun awaitExit(): TerminalFailure? {
            throw IllegalStateException("await exit failed")
        }
    }

    private class ThrowingSendBackend(
        override val identity: TerminalIdentity,
    ) : TerminalBackend {
        private val exitResult = CompletableDeferred<TerminalFailure?>()

        override val output: Flow<OutputChunk> = emptyFlow()

        override suspend fun start(openOptions: TerminalOpenOptions): BackendStartResult = BackendStartResult.Started

        override suspend fun send(input: TerminalBytes): SendResult {
            throw IllegalStateException("send failed")
        }

        override suspend fun close() = Unit

        override suspend fun awaitExit(): TerminalFailure? = exitResult.await()

        fun finishNormally() {
            if (!exitResult.isCompleted) {
                exitResult.complete(null)
            }
        }
    }

    private class FailedSendBackend(
        override val identity: TerminalIdentity,
        private val failure: TerminalFailure,
    ) : TerminalBackend {
        private val exitResult = CompletableDeferred<TerminalFailure?>()

        override val output: Flow<OutputChunk> = emptyFlow()

        override suspend fun start(openOptions: TerminalOpenOptions): BackendStartResult = BackendStartResult.Started

        override suspend fun send(input: TerminalBytes): SendResult = SendResult.Failed(failure)

        override suspend fun close() = Unit

        override suspend fun awaitExit(): TerminalFailure? = exitResult.await()

        fun finishNormally() {
            if (!exitResult.isCompleted) {
                exitResult.complete(null)
            }
        }
    }

    private class ThrowingOutputBackend(
        override val identity: TerminalIdentity,
    ) : TerminalBackend {
        private val outputFailure = CompletableDeferred<Unit>()
        private val exitResult = CompletableDeferred<TerminalFailure?>()

        override val output: Flow<OutputChunk> = flow {
            outputFailure.await()
            throw IllegalStateException("output failed")
        }

        override suspend fun start(openOptions: TerminalOpenOptions): BackendStartResult = BackendStartResult.Started

        override suspend fun send(input: TerminalBytes): SendResult = SendResult.Sent

        override suspend fun close() = Unit

        override suspend fun awaitExit(): TerminalFailure? = exitResult.await()

        fun failOutputCollection() {
            if (!outputFailure.isCompleted) {
                outputFailure.complete(Unit)
            }
        }
    }

    private fun bytesOf(text: String): TerminalBytes = TerminalBytes.of(text.encodeToByteArray())
}
