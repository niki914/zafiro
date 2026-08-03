package com.niki914.libterm.runtime

import com.niki914.libterm.BackendAvailability
import com.niki914.libterm.BackendStartResult
import com.niki914.libterm.Clock
import com.niki914.libterm.IdGenerator
import com.niki914.libterm.OpenResult
import com.niki914.libterm.OutputChunk
import com.niki914.libterm.OutputStream
import com.niki914.libterm.PrivilegeProvider
import com.niki914.libterm.SendResult
import com.niki914.libterm.TerminalBackend
import com.niki914.libterm.TerminalBytes
import com.niki914.libterm.TerminalFailure
import com.niki914.libterm.TerminalIdentity
import com.niki914.libterm.TerminalManager
import com.niki914.libterm.TerminalOpenOptions
import com.niki914.libterm.TerminalSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LibTermRuntimeTest {

    @Test
    fun `runtime reuses one wrapper for the same terminal session id`() = runTest {
        val runtime = LibTermRuntime(createManager(StreamingBackend(TerminalIdentity.User)))

        val opened = assertIs<OpenResult.Success<LibTermSession>>(
            runtime.open { identity = TerminalIdentity.User },
        )

        assertSame(opened.value, runtime.getSession(opened.value.id))
        assertSame(opened.value, runtime.listSessions().single())
        runtime.close(opened.value.id)
    }

    @Test
    fun `session close does not wait behind blocked input writes`() = runTest {
        val sendEntered = CompletableDeferred<Unit>()
        val releaseSend = CompletableDeferred<Unit>()
        val backend = StreamingBackend(
            identity = TerminalIdentity.User,
            onSend = {
                sendEntered.complete(Unit)
                releaseSend.await()
            },
        )
        val terminalSession = TerminalSession(
            id = "session-1",
            backend = backend,
            clock = FixedClock,
            scope = backgroundScope,
        )
        assertIs<com.niki914.libterm.SessionState.Running>(terminalSession.start())
        val wrapper = LibTermSession(
            session = terminalSession,
            closeSession = { true },
            decoder = PassthroughSessionDecoder(),
        )

        val writing = async { wrapper.write("blocked") }
        runCurrent()
        sendEntered.await()

        val closing = async { wrapper.close() }
        runCurrent()

        assertTrue(closing.isCompleted)
        releaseSend.complete(Unit)
        writing.await()
        closing.await()
        terminalSession.close()
    }

    @Test
    fun `open does not convert caller illegal argument to ssh open failure`() = runTest {
        val runtime = LibTermRuntime(createManager(StreamingBackend(TerminalIdentity.User)))

        val error = assertFailsWith<IllegalArgumentException> {
            runtime.open {
                throw IllegalArgumentException("caller bug")
            }
        }

        assertEquals("caller bug", error.message)
    }

    @Test
    fun `outputDecode overrides outputDecoder`() = runTest {
        val backend = StreamingBackend(TerminalIdentity.User)
        var createSessionDecoderCallCount = 0
        val runtime = LibTermRuntime(
            manager = createManager(backend),
            config = LibTermRuntimeConfig().apply {
                outputDecoder = object : TerminalOutputDecoder {
                    override fun createSessionDecoder(): SessionTerminalOutputDecoder {
                        createSessionDecoderCallCount += 1
                        return PassthroughSessionDecoder(prefix = "decoder:")
                    }
                }
                outputDecode = { chunk ->
                    "lambda:${chunk.bytes.toByteArray().decodeToString()}"
                }
            },
        )

        val opened = assertIs<OpenResult.Success<LibTermSession>>(
            runtime.open { identity = TerminalIdentity.User },
        )
        val rendered = mutableListOf<TerminalTextChunk>()
        val collectJob = backgroundScope.launch {
            opened.value.stream.collect { rendered += it }
        }
        runCurrent()
        backend.emitStdout("hello")
        runCurrent()

        assertEquals(0, createSessionDecoderCallCount)
        assertEquals(
            listOf(
                TerminalTextChunk(
                    stream = OutputStream.STDOUT,
                    text = "lambda:hello",
                    timestampMillis = 100L,
                ),
            ),
            rendered,
        )
        collectJob.cancel()
        runtime.closeAll()
    }

    @Test
    fun `runtime uses configured outputDecoder when outputDecode is absent`() = runTest {
        val backend = StreamingBackend(TerminalIdentity.User)
        var createSessionDecoderCallCount = 0
        val runtime = LibTermRuntime(
            manager = createManager(backend),
            config = LibTermRuntimeConfig().apply {
                outputDecoder = object : TerminalOutputDecoder {
                    override fun createSessionDecoder(): SessionTerminalOutputDecoder {
                        createSessionDecoderCallCount += 1
                        return PassthroughSessionDecoder(prefix = "custom:")
                    }
                }
            },
        )

        val opened = assertIs<OpenResult.Success<LibTermSession>>(
            runtime.open { identity = TerminalIdentity.User },
        )
        val rendered = mutableListOf<String>()
        val collectJob = backgroundScope.launch {
            opened.value.stream.collect { rendered += it.text }
        }
        runCurrent()
        backend.emitStdout("hello")
        runCurrent()

        assertEquals(1, createSessionDecoderCallCount)
        assertEquals(listOf("custom:hello"), rendered)
        collectJob.cancel()
        runtime.closeAll()
    }

    @Test
    fun `runtime creates isolated session decoders per session`() = runTest {
        val backends = mutableListOf<StreamingBackend>()
        val runtime = LibTermRuntime(
            manager = createManager { identity ->
                StreamingBackend(identity).also(backends::add)
            },
            config = LibTermRuntimeConfig().apply {
                outputDecoder = object : TerminalOutputDecoder {
                    override fun createSessionDecoder(): SessionTerminalOutputDecoder {
                        return object : SessionTerminalOutputDecoder {
                            private var chunkCount = 0

                            override fun decode(chunk: OutputChunk): List<TerminalTextChunk> {
                                chunkCount += 1
                                return listOf(
                                    TerminalTextChunk(
                                        stream = chunk.stream,
                                        text = "#$chunkCount:${chunk.bytes.toByteArray().decodeToString()}",
                                        timestampMillis = chunk.timestampMillis,
                                    ),
                                )
                            }

                            override fun reset() = Unit
                        }
                    }
                }
            },
        )

        val first = assertIs<OpenResult.Success<LibTermSession>>(
            runtime.open { identity = TerminalIdentity.User },
        )
        val second = assertIs<OpenResult.Success<LibTermSession>>(
            runtime.open { identity = TerminalIdentity.Su },
        )
        val firstRendered = mutableListOf<String>()
        val secondRendered = mutableListOf<String>()
        val firstCollectJob = backgroundScope.launch {
            first.value.stream.collect { firstRendered += it.text }
        }
        val secondCollectJob = backgroundScope.launch {
            second.value.stream.collect { secondRendered += it.text }
        }
        runCurrent()
        backends[0].emitStdout("one")
        backends[1].emitStdout("two")
        runCurrent()

        assertEquals(listOf("#1:one"), firstRendered)
        assertEquals(listOf("#1:two"), secondRendered)
        firstCollectJob.cancel()
        secondCollectJob.cancel()
        runtime.closeAll()
    }

    @Test
    fun `session latest includes startup output emitted before wrapper pipeline attachment`() = runTest {
        val backend = StreamingBackend(TerminalIdentity.User).also { streamingBackend ->
            streamingBackend.onStart = {
                streamingBackend.emitStdout("banner")
            }
        }
        val runtime = LibTermRuntime(createManager(backend))

        val opened = assertIs<OpenResult.Success<LibTermSession>>(
            runtime.open { identity = TerminalIdentity.User },
        )
        runCurrent()

        assertEquals(
            listOf(
                TerminalTextChunk(
                    stream = OutputStream.STDOUT,
                    text = "banner",
                    timestampMillis = 100L,
                ),
            ),
            opened.value.latest(10),
        )
        runtime.closeAll()
    }

    @Test
    fun `session latest trims decoded buffer to bounded window`() = runTest {
        val backend = StreamingBackend(TerminalIdentity.User)
        val runtime = LibTermRuntime(createManager(backend))
        val opened = assertIs<OpenResult.Success<LibTermSession>>(
            runtime.open { identity = TerminalIdentity.User },
        )
        repeat(300) { index ->
            backend.emitStdout("line-$index")
            runCurrent()
        }

        val latest = opened.value.latest(300)

        assertEquals(256, latest.size)
        assertEquals("line-44", latest.first().text)
        assertEquals("line-299", latest.last().text)
        runtime.closeAll()
    }

    private fun TestScope.createManager(backend: TerminalBackend): TerminalManager {
        return createManager { backend }
    }

    private fun TestScope.createManager(backendFactory: (TerminalIdentity) -> TerminalBackend): TerminalManager {
        return TerminalManager(
            privilegeProvider = AvailableProvider,
            idGenerator = SequentialIdGenerator(),
            clock = FixedClock,
            scope = backgroundScope,
            backendFactory = { identity, _ -> backendFactory(identity) },
        )
    }

    private object AvailableProvider : PrivilegeProvider {
        override suspend fun getAvailability(identity: TerminalIdentity): BackendAvailability {
            return BackendAvailability.Available
        }
    }

    private class SequentialIdGenerator : IdGenerator {
        private var next = 0

        override fun nextId(): String {
            next += 1
            return "session-$next"
        }
    }

    private object FixedClock : Clock {
        override fun nowMillis(): Long = 100L
    }

    private class StreamingBackend(
        override val identity: TerminalIdentity,
        private val onSend: suspend (TerminalBytes) -> Unit = {},
    ) : TerminalBackend {
        private val outputEvents = MutableSharedFlow<OutputChunk>(
            replay = 0,
            extraBufferCapacity = 16,
        )
        private val exitResult = CompletableDeferred<TerminalFailure?>()
        var onStart: (suspend () -> Unit)? = null

        override val output: Flow<OutputChunk> = outputEvents

        override suspend fun start(openOptions: TerminalOpenOptions): BackendStartResult {
            onStart?.invoke()
            return BackendStartResult.Started
        }

        override suspend fun send(input: TerminalBytes): SendResult {
            onSend(input)
            return SendResult.Sent
        }

        override suspend fun close() {
            if (!exitResult.isCompleted) {
                exitResult.complete(null)
            }
        }

        override suspend fun awaitExit(): TerminalFailure? = exitResult.await()

        fun emitStdout(
            text: String,
            timestampMillis: Long = 100L,
        ) {
            outputEvents.tryEmit(
                OutputChunk(
                    stream = OutputStream.STDOUT,
                    bytes = TerminalBytes.of(text.encodeToByteArray()),
                    timestampMillis = timestampMillis,
                ),
            )
        }
    }

    private class PassthroughSessionDecoder(
        private val prefix: String = "",
    ) : SessionTerminalOutputDecoder {
        override fun decode(chunk: OutputChunk): List<TerminalTextChunk> {
            return listOf(
                TerminalTextChunk(
                    stream = chunk.stream,
                    text = prefix + chunk.bytes.toByteArray().decodeToString(),
                    timestampMillis = chunk.timestampMillis,
                ),
            )
        }

        override fun reset() = Unit
    }
}
