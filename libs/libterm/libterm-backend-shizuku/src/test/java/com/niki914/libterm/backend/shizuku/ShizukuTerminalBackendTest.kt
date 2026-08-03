package com.niki914.libterm.backend.shizuku

import com.niki914.libterm.BackendStartResult
import com.niki914.libterm.Clock
import com.niki914.libterm.OutputChunk
import com.niki914.libterm.OutputStream
import com.niki914.libterm.SendResult
import com.niki914.libterm.TerminalBytes
import com.niki914.libterm.TerminalFailure
import com.niki914.libterm.TerminalIdentity
import com.niki914.libterm.TerminalOpenOptions
import com.niki914.libterm.backend.shizuku.internal.InvalidShizukuCwdException
import com.niki914.libterm.backend.shizuku.internal.ShizukuShellClient
import com.niki914.libterm.backend.shizuku.internal.ShizukuShellClientFactory
import com.niki914.libterm.backend.shizuku.internal.ShizukuShellOutputEvent
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
class ShizukuTerminalBackendTest {

    @Test
    fun `start opens client and returns started`() = runTest {
        val clientFactory = FakeShizukuShellClientFactory()
        val backend = createBackend(clientFactory = clientFactory)

        val result = backend.start()
        backend.close()

        assertEquals(BackendStartResult.Started, result)
        assertEquals(1, clientFactory.openCallCount)
        assertEquals(1, clientFactory.client.closeCallCount)
    }

    @Test
    fun `stdout bytes map to output chunk stdout`() = runTest {
        val clock = FakeClock(initialMillis = 100L)
        val client = FakeShizukuShellClient()
        val backend = createBackend(
            clock = clock,
            clientFactory = FakeShizukuShellClientFactory(client = client),
        )
        val collecting = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) {
            backend.output.take(1).toList()
        }

        runCurrent()
        assertEquals(BackendStartResult.Started, backend.start())
        client.emitStdout(bytesOf("hello"))
        advanceUntilIdle()
        backend.close()

        assertEquals(
            listOf(
                OutputChunk(
                    stream = OutputStream.STDOUT,
                    bytes = bytesOf("hello"),
                    timestampMillis = 100L
                )
            ),
            collecting.await(),
        )
    }

    @Test
    fun `stderr bytes map to output chunk stderr`() = runTest {
        val clock = FakeClock(initialMillis = 200L)
        val client = FakeShizukuShellClient()
        val backend = createBackend(
            clock = clock,
            clientFactory = FakeShizukuShellClientFactory(client = client),
        )
        val collecting = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) {
            backend.output.take(1).toList()
        }

        runCurrent()
        assertEquals(BackendStartResult.Started, backend.start())
        client.emitStderr(TerminalBytes.of(byteArrayOf(0x1B, 0x5B, 0x33, 0x31, 0x6D)))
        advanceUntilIdle()
        backend.close()

        assertEquals(
            listOf(
                OutputChunk(
                    stream = OutputStream.STDERR,
                    bytes = TerminalBytes.of(byteArrayOf(0x1B, 0x5B, 0x33, 0x31, 0x6D)),
                    timestampMillis = 200L,
                ),
            ),
            collecting.await(),
        )
    }

    @Test
    fun `send forwards terminal bytes without decoding`() = runTest {
        val client = FakeShizukuShellClient()
        val backend = createBackend(
            clientFactory = FakeShizukuShellClientFactory(client = client),
        )
        val rawInput = TerminalBytes.of(byteArrayOf(0x00, 0xC3.toByte(), 0x0A))

        assertEquals(BackendStartResult.Started, backend.start())
        assertEquals(SendResult.Sent, backend.send(rawInput))
        backend.close()

        assertEquals(listOf(rawInput), client.writes)
    }

    @Test
    fun `send before start returns already closed`() = runTest {
        val clientFactory = FakeShizukuShellClientFactory()
        val backend = createBackend(clientFactory = clientFactory)

        val result = backend.send(bytesOf("id"))

        val failed = assertIs<SendResult.Failed>(result)
        val failure = assertIs<TerminalFailure.AlreadyClosed>(failed.failure)
        assertEquals(TerminalIdentity.Shizuku, failure.identity)
        assertEquals("Shizuku backend has not been started", failure.message)
        assertEquals(0, clientFactory.openCallCount)
    }

    @Test
    fun `client death maps to runtime terminated`() = runTest {
        val failure = TerminalFailure.RuntimeTerminated(
            identity = TerminalIdentity.Shizuku,
            message = "service died",
        )
        val client = FakeShizukuShellClient(exitFailure = failure)
        val backend = createBackend(
            clientFactory = FakeShizukuShellClientFactory(client = client),
        )

        assertEquals(BackendStartResult.Started, backend.start())
        val result = backend.awaitExit()
        backend.close()

        assertEquals(failure, result)
    }

    @Test
    fun `close is idempotent`() = runTest {
        val client = FakeShizukuShellClient()
        val backend = createBackend(
            clientFactory = FakeShizukuShellClientFactory(client = client),
        )

        assertEquals(BackendStartResult.Started, backend.start())
        backend.close()
        backend.close()

        assertEquals(1, client.closeCallCount)
    }

    @Test
    fun `non cwd open failure with cwd maps to startup failed`() = runTest {
        val backend = createBackend(
            clientFactory = ThrowingShizukuShellClientFactory(
                failure = IllegalStateException("binder died"),
            ),
        )

        val result = backend.start(TerminalOpenOptions(cwd = "/missing"))

        val failed = assertIs<BackendStartResult.Failed>(result)
        assertIs<TerminalFailure.StartupFailed>(failed.failure)
    }

    @Test
    fun `explicit cwd open failure maps to invalid open options`() = runTest {
        val backend = createBackend(
            clientFactory = ThrowingShizukuShellClientFactory(
                failure = InvalidShizukuCwdException("Invalid cwd: /missing"),
            ),
        )

        val result = backend.start(TerminalOpenOptions(cwd = "/missing"))

        val failed = assertIs<BackendStartResult.Failed>(result)
        assertIs<TerminalFailure.InvalidOpenOptions>(failed.failure)
    }

    private fun TestScope.createBackend(
        clock: Clock = FakeClock(),
        clientFactory: ShizukuShellClientFactory,
    ): ShizukuTerminalBackend {
        return ShizukuTerminalBackend(
            clock = clock,
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            clientFactory = clientFactory,
        )
    }

    private fun bytesOf(text: String): TerminalBytes = TerminalBytes.of(text.encodeToByteArray())

    private class FakeClock(initialMillis: Long = 0L) : Clock {
        private val currentMillis: Long = initialMillis

        override fun nowMillis(): Long = currentMillis
    }

    private class FakeShizukuShellClientFactory(
        val client: FakeShizukuShellClient = FakeShizukuShellClient(),
    ) : ShizukuShellClientFactory {
        var openCallCount: Int = 0
            private set

        override suspend fun open(cwd: String): ShizukuShellClient {
            openCallCount += 1
            return client
        }
    }

    private class ThrowingShizukuShellClientFactory(
        private val failure: Throwable,
    ) : ShizukuShellClientFactory {
        override suspend fun open(cwd: String): ShizukuShellClient {
            throw failure
        }
    }

    private class FakeShizukuShellClient(
        private val exitFailure: TerminalFailure? = null,
    ) : ShizukuShellClient {
        private val outputEvents = MutableSharedFlow<ShizukuShellOutputEvent>(
            replay = 2,
            extraBufferCapacity = 16,
        )

        override val output = outputEvents

        val writes = mutableListOf<TerminalBytes>()

        var closeCallCount: Int = 0
            private set

        override suspend fun write(input: TerminalBytes): SendResult {
            writes += input
            return SendResult.Sent
        }

        override suspend fun close() {
            closeCallCount += 1
        }

        override suspend fun awaitExit(): TerminalFailure? = exitFailure

        suspend fun emitStdout(bytes: TerminalBytes) {
            outputEvents.emit(ShizukuShellOutputEvent.Stdout(bytes))
        }

        suspend fun emitStderr(bytes: TerminalBytes) {
            outputEvents.emit(ShizukuShellOutputEvent.Stderr(bytes))
        }
    }
}
