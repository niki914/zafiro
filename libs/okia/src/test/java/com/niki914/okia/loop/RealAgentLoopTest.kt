package com.niki914.okia.loop

import com.niki914.okia.error.LLMErrorCode
import com.niki914.okia.event.TurnEvent
import com.niki914.okia.fake.FakeHttpEngine
import com.niki914.okia.fake.FakeProtocolMapper
import com.niki914.okia.message.AssistantMessage
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.okia.message.StopReason
import com.niki914.okia.protocol.ProtocolEvent
import com.niki914.okia.protocol.RequestSnapshot
import com.niki914.okia.tooling.EmptyToolRegistry
import com.niki914.okia.transport.HttpTimeouts
import com.niki914.okia.transport.SseLine
import com.niki914.okia.transport.StreamResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RealAgentLoopTest {

    // ── fixtures ───────────────────────────────────────────────────────────

    private fun user(text: String) = Message.User(listOf(ContentBlock.Text(text)))

    private fun textOf(message: AssistantMessage): String =
        (message.content.single() as ContentBlock.Text).text

    private fun loopRequest(
        events: Flow<ProtocolEvent>,
        history: List<Message> = listOf(user("hi")),
        engine: FakeHttpEngine = FakeHttpEngine(),
        onCommit: suspend (List<Message>) -> Unit = {}
    ): LoopRequest = LoopRequest(
        snapshot = RequestSnapshot(
            endpoint = "https://api.test/v1",
            apiKey = "test-key",
            model = "test-model",
            systemPrompt = null,
            temperature = 0.7f,
            maxTokens = 100,
            headers = emptyMap(),
            timeouts = HttpTimeouts(1_000, 1_000, 1_000),
            tools = emptyList()
        ),
        history = history,
        input = "hi",
        options = LoopOptions(),
        idleTimeoutSeconds = null,
        toolRegistry = EmptyToolRegistry(),
        protocolMapper = FakeProtocolMapper(events),
        hooks = emptyList(),
        httpEngine = engine,
        retryPolicy = com.niki914.okia.error.RetryPolicy(),
        onCommit = onCommit
    )

    private fun loopRequest(
        events: List<ProtocolEvent>,
        engine: FakeHttpEngine = FakeHttpEngine(),
        onCommit: suspend (List<Message>) -> Unit = {}
    ): LoopRequest = loopRequest(events.asFlow(), engine = engine, onCommit = onCommit)

    private fun completed(stopReason: StopReason? = StopReason.Stop) =
        ProtocolEvent.Completed(stopReason = stopReason)

    private suspend fun runLoop(
        request: LoopRequest,
        emitted: MutableList<TurnEvent> = mutableListOf()
    ): TurnResult = RealAgentLoop().run(request) { emitted += it }

    // ── 回合生命周期 ───────────────────────────────────────────────────────

    @Test
    fun firstEventIsTurnStarted() = runTest {
        val emitted = mutableListOf<TurnEvent>()
        val result = runLoop(loopRequest(listOf(completed())), emitted)

        assertEquals(TurnResult.Completed(CompletionReason.Stop), result)
        assertEquals(TurnEvent.TurnStarted("hi"), emitted.first())
    }

    @Test
    fun streamingTextEmitsStartedDeltaEndedWithGrowingPartial() = runTest {
        val emitted = mutableListOf<TurnEvent>()
        val commits = mutableListOf<List<Message>>()
        val result = RealAgentLoop().run(
            loopRequest(
                listOf(ProtocolEvent.TextDelta("hel"), ProtocolEvent.TextDelta("lo"), completed())
            ) { commits += it },
            emitted::add
        )

        assertEquals(TurnResult.Completed(CompletionReason.Stop), result)

        val started = emitted[1] as TurnEvent.TextStarted
        assertEquals(0, started.index)
        assertEquals("hel", textOf(started.partial))

        val delta = emitted[2] as TurnEvent.TextDelta
        assertEquals("lo", delta.delta)
        assertEquals("hello", textOf(delta.partial))

        val ended = emitted[3] as TurnEvent.TextEnded
        assertEquals("hello", ended.content)
        assertEquals("hello", textOf(ended.partial))

        val turnCompleted = emitted[4] as TurnEvent.TurnCompleted
        assertEquals(StopReason.Stop, turnCompleted.message.stopReason)

        assertEquals(listOf("hello"), commits.single().map { (it as Message.Assistant).message }.map { textOf(it) })
    }

    @Test
    fun completedMapsLengthReason() = runTest {
        val result = runLoop(loopRequest(listOf(completed(StopReason.Length))))
        assertEquals(TurnResult.Completed(CompletionReason.Length), result)
    }

    @Test
    fun completedWithoutStopReasonDefaultsToStop() = runTest {
        val result = runLoop(loopRequest(listOf(completed(null))))
        assertEquals(TurnResult.Completed(CompletionReason.Stop), result)
    }

    @Test
    fun emptyResponseCommitsEmptyAssistant() = runTest {
        val commits = mutableListOf<List<Message>>()
        val emitted = mutableListOf<TurnEvent>()
        runLoop(loopRequest(listOf(completed())) { commits += it }, emitted)

        val assistant = commits.single().single() as Message.Assistant
        assertTrue(assistant.message.content.isEmpty())
        assertEquals(StopReason.Stop, assistant.message.stopReason)
        assertTrue(emitted.none { it is TurnEvent.TextStarted })
        assertEquals(1, emitted.count { it is TurnEvent.TurnCompleted })
    }

    // ── 失败路径 ───────────────────────────────────────────────────────────

    @Test
    fun errorMidStreamCommitsPartialAndFails() = runTest {
        val commits = mutableListOf<List<Message>>()
        val emitted = mutableListOf<TurnEvent>()
        val result = runLoop(
            loopRequest(
                listOf(ProtocolEvent.TextDelta("par"), ProtocolEvent.Error(RuntimeException("boom")))
            ) { commits += it },
            emitted
        )

        val failed = result as TurnResult.Failed
        assertEquals(LLMErrorCode.Parse, failed.error.code)
        assertEquals("par", textOf((commits.single().single() as Message.Assistant).message))
        assertTrue(emitted.any { it is TurnEvent.TurnFailed && textOf(it.message) == "par" })
    }

    @Test
    fun errorBeforeAnyTextCommitsNothing() = runTest {
        val commits = mutableListOf<List<Message>>()
        val result = runLoop(
            loopRequest(listOf(ProtocolEvent.Error(RuntimeException("boom")))) { commits += it }
        )

        assertEquals(LLMErrorCode.Parse, (result as TurnResult.Failed).error.code)
        assertTrue(commits.isEmpty())
    }

    @Test
    fun streamEndingWithoutCompletedFails() = runTest {
        val result = runLoop(loopRequest(listOf(ProtocolEvent.TextDelta("par"))))
        assertEquals(LLMErrorCode.Parse, (result as TurnResult.Failed).error.code)
    }

    @Test
    fun buildRequestFailureFailsWithParse() = runTest {
        val mapper = FakeProtocolMapper(listOf<ProtocolEvent>())
        mapper.buildRequestError = RuntimeException("bad request")
        val request = loopRequest(emptyFlow()).copy(protocolMapper = mapper)

        val result = runLoop(request)
        assertEquals(LLMErrorCode.Parse, (result as TurnResult.Failed).error.code)
    }

    @Test
    fun streamFailureFailsWithTransport() = runTest {
        val engine = FakeHttpEngine().apply { streamError = RuntimeException("network down") }
        val result = runLoop(loopRequest(listOf(completed()), engine = engine))
        assertEquals(LLMErrorCode.Transport, (result as TurnResult.Failed).error.code)
    }

    @Test
    fun abnormalCompletionStopReasonFails() = runTest {
        val result = runLoop(loopRequest(listOf<ProtocolEvent>(completed(StopReason.Error))))
        assertEquals(LLMErrorCode.Parse, (result as TurnResult.Failed).error.code)
    }

    // ── 取消契约 ───────────────────────────────────────────────────────────

    @Test
    fun cancellationCommitsPartialAndRethrows() = runTest {
        val events = MutableSharedFlow<ProtocolEvent>(extraBufferCapacity = 16)
        val commits = mutableListOf<List<Message>>()
        var caught: CancellationException? = null
        val job = launch {
            try {
                RealAgentLoop().run(loopRequest(events) { commits += it }) { }
            } catch (e: CancellationException) {
                caught = e
            }
        }
        runCurrent() // 让 collect 开始订阅

        events.emit(ProtocolEvent.TextDelta("par"))
        runCurrent()
        assertTrue(commits.isEmpty())

        job.cancel()
        runCurrent()

        assertTrue(caught != null)
        assertEquals("par", textOf((commits.single().single() as Message.Assistant).message))
    }

    // ── 前置校验（T3）：非 2xx / HTML 不进 SSE 解析 ─────────────────────

    private fun errorResponse(status: Int, body: String = "boom", contentType: String? = null) =
        StreamResponse.Error(
            status,
            contentType?.let { mapOf("Content-Type" to it) } ?: emptyMap(),
            body
        )

    private fun okResponse(contentType: String? = null, lines: Flow<SseLine> = emptyFlow()) =
        StreamResponse.Ok(200, contentType?.let { mapOf("Content-Type" to it) } ?: emptyMap(), lines)

    @Test
    fun non2xx429MapsToRateLimit() = runTest {
        val engine = FakeHttpEngine().apply { streamResult = { errorResponse(429) } }
        val result = runLoop(loopRequest(listOf(completed()), engine = engine))
        val failed = result as TurnResult.Failed
        assertEquals(LLMErrorCode.RateLimit, failed.error.code)
        assertEquals(429, failed.error.statusCode)
    }

    @Test
    fun non2xx401MapsToAuth() = runTest {
        val engine = FakeHttpEngine().apply { streamResult = { errorResponse(401) } }
        val result = runLoop(loopRequest(listOf(completed()), engine = engine))
        assertEquals(LLMErrorCode.Auth, (result as TurnResult.Failed).error.code)
    }

    @Test
    fun non2xx403MapsToAuth() = runTest {
        val engine = FakeHttpEngine().apply { streamResult = { errorResponse(403) } }
        val result = runLoop(loopRequest(listOf(completed()), engine = engine))
        assertEquals(LLMErrorCode.Auth, (result as TurnResult.Failed).error.code)
    }

    @Test
    fun non2xx500MapsToTransport() = runTest {
        val engine = FakeHttpEngine().apply { streamResult = { errorResponse(500) } }
        val result = runLoop(loopRequest(listOf(completed()), engine = engine))
        assertEquals(LLMErrorCode.Transport, (result as TurnResult.Failed).error.code)
    }

    @Test
    fun non2xx503MapsToOverloaded() = runTest {
        val engine = FakeHttpEngine().apply { streamResult = { errorResponse(503) } }
        val result = runLoop(loopRequest(listOf(completed()), engine = engine))
        assertEquals(LLMErrorCode.Overloaded, (result as TurnResult.Failed).error.code)
    }

    @Test
    fun non2xx400MapsToParse() = runTest {
        val engine = FakeHttpEngine().apply { streamResult = { errorResponse(400) } }
        val result = runLoop(loopRequest(listOf(completed()), engine = engine))
        assertEquals(LLMErrorCode.Parse, (result as TurnResult.Failed).error.code)
    }

    @Test
    fun non2xxBodyAndStatusInError() = runTest {
        val engine = FakeHttpEngine().apply {
            streamResult = { errorResponse(429, body = "{\"error\": {\"message\": \"rate limited\"}}") }
        }
        val result = runLoop(loopRequest(listOf(completed()), engine = engine))
        val failed = result as TurnResult.Failed
        assertEquals("{\"error\": {\"message\": \"rate limited\"}}", failed.error.message)
        assertEquals(429, failed.error.statusCode)
    }

    @Test
    fun non2xxSkipsParseStream() = runTest {
        val mapper = FakeProtocolMapper(listOf<ProtocolEvent>())
        val engine = FakeHttpEngine().apply { streamResult = { errorResponse(503) } }
        runLoop(loopRequest(emptyFlow(), engine = engine).copy(protocolMapper = mapper))
        assertEquals(0, mapper.parseStreamCalls)
    }

    @Test
    fun non2xxEmitsTurnFailed() = runTest {
        val emitted = mutableListOf<TurnEvent>()
        val engine = FakeHttpEngine().apply { streamResult = { errorResponse(503) } }
        runLoop(loopRequest(listOf(completed()), engine = engine), emitted)
        assertTrue(emitted.any { it is TurnEvent.TurnFailed })
    }

    @Test
    fun htmlContentTypeFailsParse() = runTest {
        val mapper = FakeProtocolMapper(listOf(completed()))
        val engine = FakeHttpEngine().apply {
            streamResult = { okResponse(contentType = "text/html; charset=utf-8", lines = flowOf(SseLine("<html>"))) }
        }
        val result = runLoop(loopRequest(emptyFlow(), engine = engine).copy(protocolMapper = mapper))
        assertEquals(LLMErrorCode.Parse, (result as TurnResult.Failed).error.code)
        assertEquals(0, mapper.parseStreamCalls)
    }

    @Test
    fun htmlContentTypeCaseInsensitive() = runTest {
        val engine = FakeHttpEngine().apply {
            streamResult = { okResponse(contentType = "TEXT/HTML") }
        }
        val result = runLoop(loopRequest(listOf(completed()), engine = engine))
        assertEquals(LLMErrorCode.Parse, (result as TurnResult.Failed).error.code)
    }

    @Test
    fun eventStreamContentTypePasses() = runTest {
        val engine = FakeHttpEngine().apply {
            streamResult = { okResponse(contentType = "text/event-stream", lines = flowOf(SseLine("data: x"))) }
        }
        val result = runLoop(loopRequest(listOf(completed()), engine = engine))
        assertEquals(TurnResult.Completed(CompletionReason.Stop), result)
    }

    @Test
    fun jsonContentTypePasses() = runTest {
        val engine = FakeHttpEngine().apply {
            streamResult = { okResponse(contentType = "application/json", lines = flowOf(SseLine("data: x"))) }
        }
        val result = runLoop(loopRequest(listOf(completed()), engine = engine))
        assertEquals(TurnResult.Completed(CompletionReason.Stop), result)
    }

    @Test
    fun missingContentTypePasses() = runTest {
        val engine = FakeHttpEngine().apply { streamResult = { okResponse(lines = flowOf(SseLine("data: x"))) } }
        val result = runLoop(loopRequest(listOf(completed()), engine = engine))
        assertEquals(TurnResult.Completed(CompletionReason.Stop), result)
    }

    @Test
    fun headerLookupIgnoresCase() = runTest {
        val engine = FakeHttpEngine().apply {
            streamResult = { StreamResponse.Ok(200, mapOf("content-type" to "text/html"), emptyFlow()) }
        }
        val result = runLoop(loopRequest(listOf(completed()), engine = engine))
        assertEquals(LLMErrorCode.Parse, (result as TurnResult.Failed).error.code)
    }
}
