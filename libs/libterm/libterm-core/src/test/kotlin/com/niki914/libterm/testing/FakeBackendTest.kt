package com.niki914.libterm.testing

import com.niki914.libterm.BackendStartResult
import com.niki914.libterm.OutputChunk
import com.niki914.libterm.OutputStream
import com.niki914.libterm.SendResult
import com.niki914.libterm.TerminalBytes
import com.niki914.libterm.TerminalFailure
import com.niki914.libterm.TerminalIdentity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FakeBackendTest {

    @Test
    fun `emit output keeps order stream and clock timestamp`() = runTest {
        val clock = FakeClock(initialMillis = 100L)
        val backend = FakeBackend(identity = TerminalIdentity.User, clock = clock)
        val outputs = mutableListOf<OutputChunk>()

        backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) {
            backend.output.take(3).toList(outputs)
        }
        runCurrent()

        assertTrue(backend.emitStdout(bytesOf("hello")))
        clock.advanceBy(5L)
        assertTrue(backend.emitStderr(bytesOf("oops")))
        clock.advanceBy(7L)
        assertTrue(
            backend.emitOutput(
                OutputChunk(
                    stream = OutputStream.STDOUT,
                    bytes = bytesOf("done"),
                    timestampMillis = clock.nowMillis(),
                ),
            ),
        )

        advanceUntilIdle()

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
                OutputChunk(
                    stream = OutputStream.STDOUT,
                    bytes = bytesOf("done"),
                    timestampMillis = 112L
                ),
            ),
            outputs,
        )
    }

    @Test
    fun `emit before collect is preserved for later subscriber`() = runTest {
        val backend = FakeBackend(identity = TerminalIdentity.User)

        assertTrue(backend.emitStdout(bytesOf("queued-1")))
        assertTrue(backend.emitStderr(bytesOf("queued-2")))

        val collector = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) {
            backend.output.take(2).toList()
        }

        advanceUntilIdle()

        assertEquals(
            listOf(
                OutputChunk(
                    stream = OutputStream.STDOUT,
                    bytes = bytesOf("queued-1"),
                    timestampMillis = 0L
                ),
                OutputChunk(
                    stream = OutputStream.STDERR,
                    bytes = bytesOf("queued-2"),
                    timestampMillis = 0L
                ),
            ),
            collector.await(),
        )
    }

    @Test
    fun `start and send record observable behavior for tests`() = runTest {
        val backend = FakeBackend(identity = TerminalIdentity.Su)

        val result = backend.start()
        assertEquals(SendResult.Sent, backend.send(bytesOf("pwd\n")))
        assertEquals(SendResult.Sent, backend.send(bytesOf("exit\n")))

        assertEquals(BackendStartResult.Started, result)
        assertEquals(1, backend.startCallCount)
        assertEquals(listOf(bytesOf("pwd\n"), bytesOf("exit\n")), backend.writes)
    }

    @Test
    fun `emit preserves non utf8 bytes`() = runTest {
        val backend = FakeBackend(identity = TerminalIdentity.User)
        val nonUtf8 = TerminalBytes.of(byteArrayOf(0xC3.toByte()))

        assertTrue(backend.emitStdout(nonUtf8))

        val emitted = backend.output.take(1).toList().single()
        assertEquals(OutputStream.STDOUT, emitted.stream)
        assertEquals(nonUtf8, emitted.bytes)
    }

    @Test
    fun `fail on start returns configured failure until cleared`() = runTest {
        val backend = FakeBackend(identity = TerminalIdentity.Shizuku)
        val failure = TerminalFailure.StartupFailed(
            identity = TerminalIdentity.Shizuku,
            message = "binder missing",
        )

        backend.failOnStart(failure)

        val failedResult = backend.start()
        backend.clearStartFailure()
        val recoveredResult = backend.start()

        assertEquals(2, backend.startCallCount)
        assertEquals(BackendStartResult.Failed(failure), failedResult)
        assertEquals(BackendStartResult.Started, recoveredResult)
    }

    @Test
    fun `close without hold completes immediately and send after close fails`() = runTest {
        val backend = FakeBackend(identity = TerminalIdentity.User)

        backend.close()

        assertEquals(1, backend.closeCallCount)
        assertTrue(backend.isClosed)

        val error = assertFailsWith<IllegalStateException> {
            backend.send(bytesOf("whoami\n"))
        }

        assertEquals("FakeBackend is closed", error.message)
    }

    @Test
    fun `await exit resumes with configured terminal result`() = runTest {
        val backend = FakeBackend(identity = TerminalIdentity.Su)
        val awaiting = backgroundScope.async { backend.awaitExit() }
        runCurrent()

        assertFalse(awaiting.isCompleted)
        backend.finishNormally()
        advanceUntilIdle()

        assertNull(awaiting.await())

        val failure = TerminalFailure.RuntimeTerminated(
            identity = TerminalIdentity.Su,
            message = "session crashed",
        )
        val failedBackend = FakeBackend(identity = TerminalIdentity.Su)
        val failedAwaiting = backgroundScope.async { failedBackend.awaitExit() }
        runCurrent()
        failedBackend.terminateWithFailure(failure)
        advanceUntilIdle()

        assertEquals(failure, failedAwaiting.await())
    }

    @Test
    fun `close can be held and repeated close stays safe`() = runTest {
        val backend = FakeBackend(identity = TerminalIdentity.User)
        backend.holdCloseUntilCompleted()

        val firstClose = backgroundScope.async { backend.close() }
        runCurrent()

        assertEquals(1, backend.closeCallCount)
        assertTrue(backend.isClosed)
        assertFalse(firstClose.isCompleted)

        backend.completeClose()
        firstClose.await()

        backend.close()

        assertEquals(2, backend.closeCallCount)
        assertTrue(backend.isClosed)
    }

    private fun bytesOf(text: String): TerminalBytes = TerminalBytes.of(text.encodeToByteArray())
}
