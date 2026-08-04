package com.niki914.libterm.backend.libsu

import com.niki914.libterm.BackendStartResult
import com.niki914.libterm.Clock
import com.niki914.libterm.OutputChunk
import com.niki914.libterm.OutputStream
import com.niki914.libterm.SendResult
import com.niki914.libterm.TerminalBytes
import com.niki914.libterm.TerminalFailure
import com.niki914.libterm.TerminalIdentity
import com.niki914.libterm.TerminalOpenOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class LibsuTerminalBackendTest {

    @Test
    fun `user start opens shared pipeline with user identity`() = runTest {
        val adapterFactory = FakeLibsuShellAdapterFactory()
        val session = FakeLibsuShellSession()
        adapterFactory.nextSession = session
        val backend = createBackend(
            identity = TerminalIdentity.User,
            adapterFactory = adapterFactory,
        )

        val result = backend.start()
        backend.close()

        assertEquals(BackendStartResult.Started, result)
        assertEquals(listOf(TerminalIdentity.User), adapterFactory.openedIdentities)
        assertEquals(1, session.closeCallCount)
    }

    @Test
    fun `root startup failure maps to explicit startup failed`() = runTest {
        val startupError = IllegalStateException("su shell failed")
        val adapterFactory = FakeLibsuShellAdapterFactory(startupError = startupError)
        val backend = createBackend(
            identity = TerminalIdentity.Su,
            adapterFactory = adapterFactory,
        )

        val result = backend.start()

        val failed = assertIs<BackendStartResult.Failed>(result)
        val failure = assertIs<TerminalFailure.StartupFailed>(failed.failure)
        assertEquals(TerminalIdentity.Su, failure.identity)
        assertEquals("su shell failed", failure.message)
        assertEquals(startupError, failure.cause)
        assertEquals(listOf(TerminalIdentity.Su), adapterFactory.openedIdentities)
    }

    @Test
    fun `stdout and stderr map to output chunks`() = runTest {
        val clock = FakeClock(initialMillis = 100L)
        val session = FakeLibsuShellSession()
        val backend = createBackend(
            identity = TerminalIdentity.User,
            clock = clock,
            adapterFactory = FakeLibsuShellAdapterFactory(nextSession = session),
        )
        val collecting = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) {
            backend.output.take(2).toList()
        }

        runCurrent()
        assertEquals(BackendStartResult.Started, backend.start())
        session.emitStdout(bytesOf("hello"))
        clock.advanceBy(5L)
        session.emitStderr(bytesOf("oops"))
        advanceUntilIdle()
        backend.close()

        assertEquals(
            listOf(
                OutputChunk(
                    stream = OutputStream.STDOUT,
                    bytes = bytesOf("hello"),
                    timestampMillis = 100L
                ),
                OutputChunk(
                    stream = OutputStream.STDERR,
                    bytes = bytesOf("oops"),
                    timestampMillis = 105L
                ),
            ),
            collecting.await(),
        )
    }

    @Test
    fun `send forwards raw input without appending newline`() = runTest {
        val session = FakeLibsuShellSession()
        val backend = createBackend(
            identity = TerminalIdentity.User,
            adapterFactory = FakeLibsuShellAdapterFactory(nextSession = session),
        )

        assertEquals(BackendStartResult.Started, backend.start())
        assertEquals(SendResult.Sent, backend.send(bytesOf("id")))
        assertEquals(SendResult.Sent, backend.send(bytesOf("pwd\n")))
        backend.close()

        assertEquals(listOf(bytesOf("id"), bytesOf("pwd\n")), session.writes)
    }

    @Test
    fun `non utf8 bytes are preserved`() = runTest {
        val session = FakeLibsuShellSession()
        val backend = createBackend(
            identity = TerminalIdentity.User,
            adapterFactory = FakeLibsuShellAdapterFactory(nextSession = session),
        )
        val nonUtf8 = TerminalBytes.of(byteArrayOf(0xC3.toByte()))
        val collecting = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) {
            backend.output.take(1).toList()
        }

        runCurrent()
        assertEquals(BackendStartResult.Started, backend.start())
        session.emitStdout(nonUtf8)
        advanceUntilIdle()
        backend.close()

        assertEquals(
            listOf(
                OutputChunk(
                    stream = OutputStream.STDOUT,
                    bytes = nonUtf8,
                    timestampMillis = 0L
                )
            ),
            collecting.await(),
        )
    }

    @Test
    fun `ansi escape bytes are preserved`() = runTest {
        val session = FakeLibsuShellSession()
        val backend = createBackend(
            identity = TerminalIdentity.User,
            adapterFactory = FakeLibsuShellAdapterFactory(nextSession = session),
        )
        val ansi = TerminalBytes.of(byteArrayOf(0x1B, 0x5B, 0x33, 0x31, 0x6D))
        val collecting = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) {
            backend.output.take(1).toList()
        }

        runCurrent()
        assertEquals(BackendStartResult.Started, backend.start())
        session.emitStderr(ansi)
        advanceUntilIdle()
        backend.close()

        assertEquals(
            listOf(OutputChunk(stream = OutputStream.STDERR, bytes = ansi, timestampMillis = 0L)),
            collecting.await(),
        )
    }

    @Test
    fun `write failure maps to SendResult Failed`() = runTest {
        val failure = TerminalFailure.RuntimeTerminated(
            identity = TerminalIdentity.User,
            message = "write failed",
        )
        val session = FakeLibsuShellSession().apply {
            failWritesWith(failure)
        }
        val backend = createBackend(
            identity = TerminalIdentity.User,
            adapterFactory = FakeLibsuShellAdapterFactory(nextSession = session),
        )

        assertEquals(BackendStartResult.Started, backend.start())

        val failed = assertIs<SendResult.Failed>(backend.send(bytesOf("id")))
        assertEquals(failure, failed.failure)
    }

    @Test
    fun `send returns failed when startup gate failure happens`() = runTest {
        val failure = TerminalFailure.RuntimeTerminated(
            identity = TerminalIdentity.User,
            message = "stdin startup gate failed",
        )
        val session = FakeLibsuShellSession().apply {
            failStartupGateWith(failure)
        }
        val backend = createBackend(
            identity = TerminalIdentity.User,
            adapterFactory = FakeLibsuShellAdapterFactory(nextSession = session),
        )

        assertEquals(BackendStartResult.Started, backend.start())

        val failed = assertIs<SendResult.Failed>(backend.send(bytesOf("id")))
        assertEquals(failure, failed.failure)
    }

    @Test
    fun `close is idempotent and releases session once`() = runTest {
        val session = FakeLibsuShellSession()
        val backend = createBackend(
            identity = TerminalIdentity.Su,
            adapterFactory = FakeLibsuShellAdapterFactory(nextSession = session),
        )

        assertEquals(BackendStartResult.Started, backend.start())
        backend.close()
        backend.close()

        assertEquals(1, session.closeCallCount)
    }

    @Test
    fun `await exit returns runtime failure from session`() = runTest {
        val failure = TerminalFailure.RuntimeTerminated(
            identity = TerminalIdentity.Su,
            message = "shell died",
        )
        val session = FakeLibsuShellSession(exitFailure = failure)
        val backend = createBackend(
            identity = TerminalIdentity.Su,
            adapterFactory = FakeLibsuShellAdapterFactory(nextSession = session),
        )

        assertEquals(BackendStartResult.Started, backend.start())
        val result = backend.awaitExit()
        backend.close()

        assertEquals(failure, result)
    }

    private fun TestScope.createBackend(
        identity: TerminalIdentity,
        clock: Clock = FakeClock(),
        adapterFactory: LibsuShellAdapterFactory,
    ): LibsuTerminalBackend {
        return LibsuTerminalBackend(
            identity = identity,
            clock = clock,
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            adapterFactory = adapterFactory,
        )
    }

    private fun bytesOf(text: String): TerminalBytes = TerminalBytes.of(text.encodeToByteArray())

    private class FakeClock(initialMillis: Long = 0L) : Clock {
        private var currentMillis: Long = initialMillis

        override fun nowMillis(): Long = currentMillis

        fun advanceBy(deltaMillis: Long) {
            currentMillis += deltaMillis
        }
    }

    private class FakeLibsuShellAdapterFactory(
        var nextSession: LibsuShellSession = FakeLibsuShellSession(),
        private val startupError: Throwable? = null,
    ) : LibsuShellAdapterFactory {
        val openedIdentities = mutableListOf<TerminalIdentity>()

        override suspend fun open(
            identity: TerminalIdentity,
            openOptions: TerminalOpenOptions,
        ): LibsuShellSession {
            openedIdentities += identity
            startupError?.let { throw it }
            return nextSession
        }
    }

    private class FakeLibsuShellSession(
        private val exitFailure: TerminalFailure? = null,
    ) : LibsuShellSession {
        private val outputEvents = MutableSharedFlow<LibsuOutputEvent>(
            replay = 2,
            extraBufferCapacity = 16,
        )

        override val output = outputEvents

        val writes = mutableListOf<TerminalBytes>()
        private var startupGateFailure: TerminalFailure.RuntimeTerminated? = null
        private var writeFailure: TerminalFailure? = null

        var closeCallCount: Int = 0
            private set

        override suspend fun write(input: TerminalBytes): SendResult {
            startupGateFailure?.let { return SendResult.Failed(it) }
            writeFailure?.let { return SendResult.Failed(it) }
            writes += input
            return SendResult.Sent
        }

        override suspend fun close() {
            closeCallCount += 1
        }

        override suspend fun awaitExit(): TerminalFailure? = exitFailure

        fun failStartupGateWith(failure: TerminalFailure.RuntimeTerminated) {
            startupGateFailure = failure
        }

        fun failWritesWith(failure: TerminalFailure) {
            writeFailure = failure
        }

        suspend fun emitStdout(bytes: TerminalBytes) {
            outputEvents.emit(LibsuOutputEvent.Stdout(bytes))
        }

        suspend fun emitStderr(bytes: TerminalBytes) {
            outputEvents.emit(LibsuOutputEvent.Stderr(bytes))
        }
    }
}
