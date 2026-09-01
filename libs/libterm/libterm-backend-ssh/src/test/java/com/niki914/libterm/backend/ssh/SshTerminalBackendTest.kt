package com.niki914.libterm.backend.ssh

import com.niki914.libterm.BackendStartResult
import com.niki914.libterm.Clock
import com.niki914.libterm.OutputChunk
import com.niki914.libterm.OutputStream
import com.niki914.libterm.SendResult
import com.niki914.libterm.SshAuth
import com.niki914.libterm.SshOpenOptions
import com.niki914.libterm.TerminalBytes
import com.niki914.libterm.TerminalFailure
import com.niki914.libterm.TerminalIdentity
import com.niki914.libterm.TerminalOpenOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.net.ConnectException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

@OptIn(ExperimentalCoroutinesApi::class)
class SshTerminalBackendTest {

    @Test
    fun `start opens adapter and returns started`() = runTest {
        val options = sshOptions()
        val adapter = FakeSshShellAdapter()
        val factory = FakeSshShellAdapterFactory(nextAdapter = adapter)
        val backend = createBackend(
            options = options,
            adapterFactory = factory,
        )

        val result = backend.start(TerminalOpenOptions(ssh = options))

        assertEquals(BackendStartResult.Started, result)
        assertEquals(TerminalIdentity.Ssh, backend.identity)
        assertEquals(listOf(options), factory.openedOptions)
        backend.close()
    }

    @Test
    fun `adapter output maps to stdout chunks`() = runTest {
        val clock = FakeClock(nowMillis = 100L)
        val options = sshOptions()
        val adapter = FakeSshShellAdapter()
        val backend = createBackend(
            options = options,
            clock = clock,
            adapterFactory = FakeSshShellAdapterFactory(nextAdapter = adapter),
        )
        val collecting = backgroundScopeAsync {
            backend.output.take(2).toList()
        }

        runCurrent()
        assertEquals(BackendStartResult.Started, backend.start(TerminalOpenOptions(ssh = options)))
        adapter.emit(bytesOf("hello"))
        clock.advanceBy(5L)
        adapter.emit(bytesOf("pwd"))
        advanceUntilIdle()
        backend.close()

        assertEquals(
            listOf(
                OutputChunk(
                    stream = OutputStream.STDOUT,
                    bytes = bytesOf("hello"),
                    timestampMillis = 100L,
                ),
                OutputChunk(
                    stream = OutputStream.STDOUT,
                    bytes = bytesOf("pwd"),
                    timestampMillis = 105L,
                ),
            ),
            collecting.await(),
        )
    }

    @Test
    fun `send forwards raw input to adapter`() = runTest {
        val options = sshOptions()
        val adapter = FakeSshShellAdapter()
        val backend = createBackend(
            options = options,
            adapterFactory = FakeSshShellAdapterFactory(nextAdapter = adapter),
        )

        assertEquals(BackendStartResult.Started, backend.start(TerminalOpenOptions(ssh = options)))
        assertEquals(SendResult.Sent, backend.send(bytesOf("id")))
        assertEquals(SendResult.Sent, backend.send(bytesOf("pwd\n")))

        assertEquals(listOf(bytesOf("id"), bytesOf("pwd\n")), adapter.writes)
        backend.close()
    }

    @Test
    fun `close releases adapter once and later send returns already closed`() = runTest {
        val options = sshOptions()
        val adapter = FakeSshShellAdapter()
        val backend = createBackend(
            options = options,
            adapterFactory = FakeSshShellAdapterFactory(nextAdapter = adapter),
        )

        assertEquals(BackendStartResult.Started, backend.start(TerminalOpenOptions(ssh = options)))
        backend.close()
        backend.close()

        val failed = assertIs<SendResult.Failed>(backend.send(bytesOf("id")))
        assertEquals(1, adapter.closeCallCount)
        assertEquals(TerminalIdentity.Ssh, failed.failure.identity)
        assertIs<TerminalFailure.AlreadyClosed>(failed.failure)
    }

    @Test
    fun `authentication startup failure maps to ssh authentication failure`() = runTest {
        val error = IllegalStateException("Auth fail")
        val options = sshOptions(username = "root")
        val backend = createBackend(
            options = options,
            adapterFactory = FakeSshShellAdapterFactory(startError = error),
        )

        val result = backend.start(TerminalOpenOptions(ssh = options))

        val failed = assertIs<BackendStartResult.Failed>(result)
        val failure = assertIs<TerminalFailure.SshAuthenticationFailed>(failed.failure)
        assertEquals("root", failure.username)
        assertEquals("Auth fail", failure.message)
        assertSame(error, failure.cause)
    }

    @Test
    fun `connection startup failure maps to ssh connection failure`() = runTest {
        val error = ConnectException("Connection refused")
        val options = sshOptions(host = "10.0.0.2", port = 2222)
        val backend = createBackend(
            options = options,
            adapterFactory = FakeSshShellAdapterFactory(startError = error),
        )

        val result = backend.start(TerminalOpenOptions(ssh = options))

        val failed = assertIs<BackendStartResult.Failed>(result)
        val failure = assertIs<TerminalFailure.SshConnectionFailed>(failed.failure)
        assertEquals("10.0.0.2", failure.host)
        assertEquals(2222, failure.port)
        assertEquals("Connection refused", failure.message)
        assertSame(error, failure.cause)
    }

    @Test
    fun `await exit maps adapter exception to runtime terminated`() = runTest {
        val error = IllegalStateException("remote died")
        val options = sshOptions()
        val adapter = FakeSshShellAdapter(exitError = error)
        val backend = createBackend(
            options = options,
            adapterFactory = FakeSshShellAdapterFactory(nextAdapter = adapter),
        )

        assertEquals(BackendStartResult.Started, backend.start(TerminalOpenOptions(ssh = options)))
        val failure = assertIs<TerminalFailure.RuntimeTerminated>(backend.awaitExit())

        assertEquals(TerminalIdentity.Ssh, failure.identity)
        assertEquals("remote died", failure.message)
        assertSame(error, failure.cause)
        backend.close()
    }

    private fun TestScope.createBackend(
        options: SshOpenOptions = sshOptions(),
        clock: Clock = FakeClock(),
        adapterFactory: SshShellAdapterFactory,
    ): SshTerminalBackend {
        return SshTerminalBackend(
            options = options,
            clock = clock,
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            adapterFactory = adapterFactory,
        )
    }

    private fun TestScope.backgroundScopeAsync(
        block: suspend CoroutineScope.() -> List<OutputChunk>,
    ) = backgroundScope.async(UnconfinedTestDispatcher(testScheduler), block = block)

    private fun sshOptions(
        host: String = "192.168.1.10",
        port: Int = SshOpenOptions.DEFAULT_PORT,
        username: String = "root",
    ): SshOpenOptions {
        return SshOpenOptions(
            host = host,
            port = port,
            username = username,
            auth = SshAuth.Password("password"),
        )
    }

    private fun bytesOf(text: String): TerminalBytes = TerminalBytes.of(text.encodeToByteArray())

    private class FakeClock(
        private var nowMillis: Long = 0L,
    ) : Clock {
        override fun nowMillis(): Long = nowMillis

        fun advanceBy(deltaMillis: Long) {
            nowMillis += deltaMillis
        }
    }

    private class FakeSshShellAdapterFactory(
        private val nextAdapter: SshShellAdapter = FakeSshShellAdapter(),
        private val startError: Throwable? = null,
    ) : SshShellAdapterFactory {
        val openedOptions = mutableListOf<SshOpenOptions>()

        override suspend fun open(options: SshOpenOptions): SshShellAdapter {
            openedOptions += options
            startError?.let { throw it }
            return nextAdapter
        }
    }

    private class FakeSshShellAdapter(
        private val exitError: Throwable? = null,
    ) : SshShellAdapter {
        private val outputEvents = MutableSharedFlow<TerminalBytes>(
            extraBufferCapacity = 16,
        )
        val writes = mutableListOf<TerminalBytes>()
        var closeCallCount: Int = 0
            private set

        override val output: Flow<TerminalBytes> = outputEvents

        override suspend fun write(input: TerminalBytes) {
            writes += input
        }

        override suspend fun close() {
            closeCallCount += 1
        }

        override suspend fun awaitExit(): Throwable? = exitError

        fun emit(bytes: TerminalBytes) {
            check(outputEvents.tryEmit(bytes)) { "Fake adapter output buffer is full" }
        }
    }
}
