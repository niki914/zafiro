package com.niki914.libterm.runtime

import com.niki914.libterm.BackendAvailability
import com.niki914.libterm.BackendStartResult
import com.niki914.libterm.Clock
import com.niki914.libterm.IdGenerator
import com.niki914.libterm.OutputChunk
import com.niki914.libterm.OutputStream
import com.niki914.libterm.PrivilegeProvider
import com.niki914.libterm.SendResult
import com.niki914.libterm.SshAuth
import com.niki914.libterm.SshOpenOptions
import com.niki914.libterm.TerminalBackend
import com.niki914.libterm.TerminalBytes
import com.niki914.libterm.TerminalFailure
import com.niki914.libterm.TerminalIdentity
import com.niki914.libterm.TerminalManager
import com.niki914.libterm.TerminalOpenOptions
import com.niki914.libterm.runtime.internal.DefaultTerm
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultTermTest {

    @Test
    fun `exec returns exit code and strips internal marker`() = runTest {
        val backend = RecordingBackend(TerminalIdentity.User)
        val lastWrite = AtomicReference<String>("")
        backend.onSend = { input ->
            val payload = input.toByteArray().decodeToString()
            lastWrite.set(payload)
            val execId = Regex("__LIBTERM_EXIT_([^:]+):%s__")
                .find(payload)
                ?.groupValues
                ?.get(1)
                ?: error("Missing exec marker")
            backend.emitStdout("hello\n")
            backend.emitStdout("\n__LIBTERM_EXIT_${execId}:7__\n")
        }
        val term = createTerm(backend)

        val result = term.exec("echo hello")

        val success = assertIs<TermResult.Success<CommandResult>>(result)
        assertEquals("echo hello", success.value.command)
        assertEquals("hello\n", success.value.stdout.toByteArray().decodeToString())
        assertEquals("", success.value.stderr.toByteArray().decodeToString())
        assertEquals(7, success.value.exitCode)
        assertFalse(success.value.timedOut)
        assertEquals(CommandTerminationReason.COMPLETED, success.value.terminationReason)
        assertTrue(lastWrite.get().contains("__libterm_exec_status=$?"))
        term.close()
    }

    @Test
    fun `exec timeout returns partial streamed output`() = runTest {
        val backend = RecordingBackend(TerminalIdentity.User)
        backend.onSend = {
            backend.emitStdout("partial-output")
        }
        val term = createTerm(backend)

        val deferred = async {
            term.exec(
                command = "cat",
                timeoutMillis = 50L,
            )
        }
        runCurrent()
        advanceTimeBy(50L)
        runCurrent()

        val result = deferred.await()

        val success = assertIs<TermResult.Success<CommandResult>>(result)
        assertTrue(success.value.timedOut)
        assertNull(success.value.exitCode)
        assertEquals(CommandTerminationReason.TIMED_OUT, success.value.terminationReason)
        assertEquals("partial-output", success.value.stdout.toByteArray().decodeToString())
        assertEquals("", success.value.stderr.toByteArray().decodeToString())
        term.close()
    }

    @Test
    fun `exec returns session terminated when shell exits before marker`() = runTest {
        val backend = RecordingBackend(TerminalIdentity.User)
        backend.onSend = {
            backend.emitStdout("STDOUT_OK\n")
            backend.emitStderr("STDERR_OK\n")
            backend.completeExit()
        }
        val term = createTerm(backend)

        val result = term.exec(
            command = "echo STDOUT_OK\necho STDERR_OK 1>&2\nexit 7",
            timeoutMillis = 5_000L,
        )

        val success = assertIs<TermResult.Success<CommandResult>>(result)
        assertEquals("STDOUT_OK\n", success.value.stdout.toByteArray().decodeToString())
        assertEquals("STDERR_OK\n", success.value.stderr.toByteArray().decodeToString())
        assertNull(success.value.exitCode)
        assertFalse(success.value.timedOut)
        assertEquals(
            CommandTerminationReason.SESSION_TERMINATED,
            success.value.terminationReason,
        )
        term.close()
    }

    @Test
    fun `concurrent first open write and exec share one session`() = runTest {
        val startGate = CompletableDeferred<Unit>()
        val backend = RecordingBackend(TerminalIdentity.User).also { recordingBackend ->
            recordingBackend.startGate = startGate
            recordingBackend.onSend = { input ->
                val payload = input.toByteArray().decodeToString()
                val execId = Regex("__LIBTERM_EXIT_([^:]+):%s__")
                    .find(payload)
                    ?.groupValues
                    ?.get(1)
                if (execId != null) {
                    recordingBackend.emitStdout("\n__LIBTERM_EXIT_${execId}:0__\n")
                }
            }
        }
        val term = createTerm(backend)

        val opening = async { term.open() }
        val writing = async { term.write("input") }
        val executing = async { term.exec("true") }
        runCurrent()

        assertEquals(1, backend.startCallCount)
        startGate.complete(Unit)
        awaitAll(opening, writing, executing)
        term.close()

        assertEquals(1, backend.startCallCount)
    }

    @Test
    fun `open passes ssh options to manager`() = runTest {
        val sshOptions = SshOpenOptions(
            host = "127.0.0.1",
            port = 2222,
            username = "bytedance",
            auth = SshAuth.Password("secret"),
        )
        val backend = RecordingBackend(TerminalIdentity.Ssh)
        val manager = TerminalManager(
            privilegeProvider = AvailableProvider,
            idGenerator = CountingIdGenerator(),
            clock = FixedClock,
            scope = backgroundScope,
            backendFactory = { _, _ -> backend },
        )
        val term = DefaultTerm(
            runtime = LibTermRuntime(manager),
            identity = TerminalIdentity.Ssh,
            authorizationMode = com.niki914.libterm.AuthorizationMode.REQUEST_IF_NEEDED,
            openOptions = TerminalOpenOptions(ssh = sshOptions),
            scope = backgroundScope,
            ownsScope = false,
        )

        val result = term.open()

        assertIs<TermResult.Success<Unit>>(result)
        assertEquals(sshOptions, backend.lastStartOptions?.ssh)
        term.close()
    }

    @Test
    fun `stream emits decoded terminal text chunks`() = runTest {
        val backend = RecordingBackend(TerminalIdentity.User)
        val term = createTerm(backend)
        val rendered = mutableListOf<TerminalTextChunk>()
        val collectJob = backgroundScope.launch {
            term.stream.collect { rendered += it }
        }
        runCurrent()

        assertIs<TermResult.Success<Unit>>(term.open())
        runCurrent()
        backend.emitStdout("\u001B[31mhello\u001B[0m")
        runCurrent()

        assertEquals(
            listOf(
                TerminalTextChunk(
                    stream = OutputStream.STDOUT,
                    text = "hello",
                    timestampMillis = 100L,
                ),
            ),
            rendered,
        )
        collectJob.cancel()
        term.close()
    }

    @Test
    fun `stream replays startup output buffered before runtime pipeline is attached`() = runTest {
        val backend = RecordingBackend(TerminalIdentity.User).also { recordingBackend ->
            recordingBackend.onStart = {
                recordingBackend.emitStdout("prompt> ")
            }
        }
        val term = createTerm(backend)
        val rendered = mutableListOf<TerminalTextChunk>()
        val collectJob = backgroundScope.launch {
            term.stream.collect { rendered += it }
        }
        runCurrent()

        assertIs<TermResult.Success<Unit>>(term.open())
        runCurrent()

        assertEquals(
            listOf(
                TerminalTextChunk(
                    stream = OutputStream.STDOUT,
                    text = "prompt> ",
                    timestampMillis = 100L,
                ),
            ),
            rendered,
        )
        collectJob.cancel()
        term.close()
    }

    @Test
    fun `late term stream subscribers still receive startup replay`() = runTest {
        val backend = RecordingBackend(TerminalIdentity.User).also { recordingBackend ->
            recordingBackend.onStart = {
                recordingBackend.emitStdout("prompt> ")
            }
        }
        val term = createTerm(backend)

        assertIs<TermResult.Success<Unit>>(term.open())
        runCurrent()

        val rendered = mutableListOf<TerminalTextChunk>()
        val collectJob = backgroundScope.launch {
            term.stream.collect { rendered += it }
        }
        runCurrent()

        assertEquals(
            listOf(
                TerminalTextChunk(
                    stream = OutputStream.STDOUT,
                    text = "prompt> ",
                    timestampMillis = 100L,
                ),
            ),
            rendered,
        )
        collectJob.cancel()
        term.close()
    }

    private fun kotlinx.coroutines.test.TestScope.createTerm(backend: RecordingBackend): Term {
        val manager = TerminalManager(
            privilegeProvider = AvailableProvider,
            idGenerator = CountingIdGenerator(),
            clock = FixedClock,
            scope = backgroundScope,
            backendFactory = { _, _ -> backend },
        )
        return DefaultTerm(
            manager = manager,
            identity = backend.identity,
            scope = backgroundScope,
            ownsScope = false,
        )
    }

    private object AvailableProvider : PrivilegeProvider {
        override suspend fun getAvailability(identity: TerminalIdentity): BackendAvailability {
            return BackendAvailability.Available
        }
    }

    private class CountingIdGenerator : IdGenerator {
        private var next = 0

        override fun nextId(): String {
            next += 1
            return "session-$next"
        }
    }

    private object FixedClock : Clock {
        override fun nowMillis(): Long = 100L
    }

    private class RecordingBackend(
        override val identity: TerminalIdentity,
    ) : TerminalBackend {
        private val outputEvents = MutableSharedFlow<OutputChunk>(
            replay = 0,
            extraBufferCapacity = 16,
        )
        private val exitResult = CompletableDeferred<TerminalFailure?>()

        var onSend: (suspend (TerminalBytes) -> Unit)? = null
        var onStart: (suspend () -> Unit)? = null
        var startGate: CompletableDeferred<Unit>? = null
        var lastStartOptions: TerminalOpenOptions? = null
            private set
        var startCallCount: Int = 0
            private set

        override val output: Flow<OutputChunk> = outputEvents

        override suspend fun start(openOptions: TerminalOpenOptions): BackendStartResult {
            startCallCount += 1
            lastStartOptions = openOptions
            onStart?.invoke()
            startGate?.await()
            return BackendStartResult.Started
        }

        override suspend fun send(input: TerminalBytes): SendResult {
            onSend?.invoke(input)
            return SendResult.Sent
        }

        override suspend fun close() {
            if (!exitResult.isCompleted) {
                exitResult.complete(null)
            }
        }

        override suspend fun awaitExit(): TerminalFailure? = exitResult.await()

        fun emitStdout(text: String) {
            outputEvents.tryEmit(
                OutputChunk(
                    stream = OutputStream.STDOUT,
                    bytes = TerminalBytes.of(text.encodeToByteArray()),
                    timestampMillis = 100L,
                ),
            )
        }

        fun emitStderr(text: String) {
            outputEvents.tryEmit(
                OutputChunk(
                    stream = OutputStream.STDERR,
                    bytes = TerminalBytes.of(text.encodeToByteArray()),
                    timestampMillis = 100L,
                ),
            )
        }

        fun completeExit() {
            exitResult.complete(null)
        }
    }
}
