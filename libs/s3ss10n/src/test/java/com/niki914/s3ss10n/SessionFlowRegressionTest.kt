package com.niki914.s3ss10n

import com.niki914.s3ss10n.ext.json.GsonJsonCodec
import com.niki914.s3ss10n.ext.protocol.ProtocolEvent
import com.niki914.s3ss10n.json.JsonCodec
import com.niki914.s3ss10n.net.HttpFrame
import com.niki914.s3ss10n.net.HttpTimeouts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SessionFlowRegressionTest {
    @Test
    fun `空 assistant 响应不写入 history 且发出 error 和 complete`() = runBlocking {
        val protocol = RecordingChatProtocol {
            flowOf(ProtocolEvent.Completed)
        }
        val session = newChatSession(protocol = protocol, engine = FakeHttpEngine())

        try {
            val events = session.send("hi").toList()
            assertEquals(
                listOf(
                    SessionEvent.RoundStarted(input = "hi"),
                    SessionEvent.Error(
                        stage = SessionEvent.Stage.Parse,
                        message = "Empty assistant response"
                    ),
                    SessionEvent.RoundCompleted(
                        fullText = "",
                        finishReason = SessionEvent.FinishReason.Error
                    )
                ),
                events
            )
            assertEquals(
                SessionEvent.FinishReason.Error,
                events.filterIsInstance<SessionEvent.RoundCompleted>().single().finishReason
            )
            assertEquals(emptyList<ChatTurn>(), session.getHistory())
        } finally {
            session.close()
        }
    }

    @Test
    fun `正常文本响应提交 user 和 assistant history`() = runBlocking {
        val protocol = RecordingChatProtocol {
            flowOf(
                ProtocolEvent.TextDelta("hello"),
                ProtocolEvent.Completed
            )
        }
        val session = newChatSession(protocol = protocol, engine = FakeHttpEngine())

        try {
            val events = session.send("hi").toList()
            assertEquals(
                listOf(
                    SessionEvent.RoundStarted(input = "hi"),
                    SessionEvent.TextDelta(delta = "hello", fullText = "hello"),
                    SessionEvent.RoundCompleted(fullText = "hello")
                ),
                events
            )
            assertEquals(
                SessionEvent.FinishReason.Completed,
                events.filterIsInstance<SessionEvent.RoundCompleted>().single().finishReason
            )
            assertEquals(
                listOf(
                    ChatTurn.User(content = "hi"),
                    ChatTurn.Assistant(content = "hello")
                ),
                session.getHistory()
            )
        } finally {
            session.close()
        }
    }

    @Test
    fun `RoundRunner 将网络帧 payload 传给 ChatProtocol`() = runBlocking {
        val receivedPayloads = mutableListOf<String>()
        val protocol = RecordingChatProtocol { payloads ->
            flow {
                val values = payloads.toList()
                receivedPayloads += values
                values.forEach { emit(ProtocolEvent.TextDelta(it)) }
                emit(ProtocolEvent.Completed)
            }
        }
        val engine = FakeHttpEngine(
            frameHandler = {
                flowOf(
                    HttpFrame.SseData(value = "hello", event = "message"),
                    HttpFrame.SseData(value = " world", event = "message")
                )
            }
        )
        val session = newChatSession(protocol = protocol, engine = engine)

        try {
            val events = session.send("hi").toList()

            assertEquals(listOf("hello", " world"), receivedPayloads)
            assertEquals(SessionEvent.RoundCompleted(fullText = "hello world"), events.last())
            assertEquals(1, engine.frameRequests.size)
        } finally {
            session.close()
        }
    }

    @Test
    fun `reasoning delta 和 signature 写入 assistant history`() = runBlocking {
        val protocol = RecordingChatProtocol {
            flowOf(
                ProtocolEvent.ReasoningDelta("why"),
                ProtocolEvent.ReasoningSignature("sig"),
                ProtocolEvent.TextDelta("answer"),
                ProtocolEvent.Completed
            )
        }
        val session = newChatSession(protocol = protocol, engine = FakeHttpEngine())

        try {
            val events = session.send("hi").toList()
            val assistant = session.getHistory().filterIsInstance<ChatTurn.Assistant>().single()

            assertEquals(SessionEvent.RoundCompleted(fullText = "answer"), events.last())
            assertEquals("answer", assistant.content)
            assertEquals("why", assistant.reasoningContent)
            assertEquals("sig", assistant.reasoningSignature)
        } finally {
            session.close()
        }
    }

    @Test
    fun `配置错误发 Error 并以 Error 完成`() = runBlocking {
        val protocol = RecordingChatProtocol {
            flowOf(ProtocolEvent.TextDelta("should not run"))
        }
        val session = newChatSession(protocol = protocol, engine = FakeHttpEngine()) {
            endpoint = "invalid-endpoint"
        }

        try {
            val events = session.send("hi").toList()
            val error = events.filterIsInstance<SessionEvent.Error>().single()
            val completed = events.filterIsInstance<SessionEvent.RoundCompleted>().single()

            assertEquals(SessionEvent.Stage.Session, error.stage)
            assertFalse(events.any { it is SessionEvent.RoundStarted })
            assertEquals(SessionEvent.FinishReason.Error, completed.finishReason)
            assertEquals("", completed.fullText)
            assertEquals(emptyList<ChatTurn>(), session.getHistory())
        } finally {
            session.close()
        }
    }

    @Test
    fun `最终 completed 事件不会重复发送`() = runBlocking {
        val protocol = RecordingChatProtocol {
            flowOf(
                ProtocolEvent.TextDelta("hel"),
                ProtocolEvent.TextDelta("lo"),
                ProtocolEvent.Completed
            )
        }
        val session = newChatSession(protocol = protocol, engine = FakeHttpEngine())

        try {
            val events = session.send("hi").toList()
            val completed = events.filterIsInstance<SessionEvent.RoundCompleted>()

            assertEquals(1, completed.size)
            assertEquals(SessionEvent.RoundCompleted(fullText = "hello"), completed.single())
            assertEquals(SessionEvent.FinishReason.Completed, completed.single().finishReason)
        } finally {
            session.close()
        }
    }

    @Test
    fun `已开始 round 发生异常时仍发出 complete`() = runBlocking {
        val protocol = RecordingChatProtocol {
            flow {
                emit(ProtocolEvent.TextDelta("hello"))
                throw IllegalStateException("boom")
            }
        }
        val session = newChatSession(protocol = protocol, engine = FakeHttpEngine())

        try {
            val events = session.send("hi").toList()
            assertEquals(4, events.size)
            assertEquals(SessionEvent.RoundStarted(input = "hi"), events[0])
            assertEquals(
                SessionEvent.TextDelta(delta = "hello", fullText = "hello"),
                events[1]
            )
            val error = events[2] as SessionEvent.Error
            assertEquals(SessionEvent.Stage.Parse, error.stage)
            assertEquals("boom", error.message)
            assertTrue(error.cause is IllegalStateException)
            assertEquals(
                SessionEvent.RoundCompleted(
                    fullText = "hello",
                    finishReason = SessionEvent.FinishReason.Error
                ),
                events[3]
            )
            assertEquals(emptyList<ChatTurn>(), session.getHistory())
        } finally {
            session.close()
        }
    }

    @Test
    fun `协议 error 后无内容不追加空 assistant error`() = runBlocking {
        val protocolError = IllegalStateException("protocol boom")
        val protocol = RecordingChatProtocol {
            flowOf(
                ProtocolEvent.Error(
                    cause = protocolError,
                    stage = SessionEvent.Stage.Parse
                ),
                ProtocolEvent.Completed
            )
        }
        val session = newChatSession(protocol = protocol, engine = FakeHttpEngine())

        try {
            val events = session.send("hi").toList()
            assertEquals(
                listOf(
                    SessionEvent.RoundStarted(input = "hi"),
                    SessionEvent.Error(
                        stage = SessionEvent.Stage.Parse,
                        message = "protocol boom",
                        cause = protocolError
                    ),
                    SessionEvent.RoundCompleted(
                        fullText = "",
                        finishReason = SessionEvent.FinishReason.Error
                    )
                ),
                events
            )
            assertEquals(
                SessionEvent.FinishReason.Error,
                events.filterIsInstance<SessionEvent.RoundCompleted>().single().finishReason
            )
            assertEquals(emptyList<ChatTurn>(), session.getHistory())
        } finally {
            session.close()
        }
    }

    @Test
    fun `协议 error 后有文本最终仍以 Error 完成`() = runBlocking {
        val protocolError = IllegalStateException("protocol boom")
        val protocol = RecordingChatProtocol {
            flowOf(
                ProtocolEvent.TextDelta("partial"),
                ProtocolEvent.Error(
                    cause = protocolError,
                    stage = SessionEvent.Stage.Parse
                ),
                ProtocolEvent.Completed
            )
        }
        val session = newChatSession(protocol = protocol, engine = FakeHttpEngine())

        try {
            val events = session.send("hi").toList()
            val completed = events.filterIsInstance<SessionEvent.RoundCompleted>().single()

            assertEquals("partial", completed.fullText)
            assertEquals(SessionEvent.FinishReason.Error, completed.finishReason)
        } finally {
            session.close()
        }
    }

    @Test
    fun `finishReason 默认兼容且正常完成为 Completed`() = runBlocking {
        val completed = SessionEvent.RoundCompleted(fullText = "done")
        val protocol = RecordingChatProtocol {
            flowOf(
                ProtocolEvent.TextDelta("done"),
                ProtocolEvent.Completed
            )
        }
        val session = newChatSession(protocol = protocol, engine = FakeHttpEngine())

        try {
            val events = session.send("hi").toList()
            val actual = events.filterIsInstance<SessionEvent.RoundCompleted>().single()

            assertEquals(SessionEvent.FinishReason.Completed, completed.finishReason)
            assertEquals(SessionEvent.FinishReason.Completed, actual.finishReason)
            assertEquals(SessionEvent.RoundCompleted(fullText = "done"), actual)
        } finally {
            session.close()
        }
    }

    @Test
    fun `stop false 取消当前轮且不提交 history`() = runBlocking {
        val textSeen = CompletableDeferred<Unit>()
        val neverRelease = CompletableDeferred<Unit>()
        val events = mutableListOf<SessionEvent>()
        val protocol = RecordingChatProtocol {
            flow {
                emit(ProtocolEvent.TextDelta("partial"))
                neverRelease.await()
                emit(ProtocolEvent.Completed)
            }
        }
        val session = newChatSession(protocol = protocol, engine = FakeHttpEngine())

        try {
            val sendJob = async {
                session.send("hi") { event ->
                    events += event
                    if (event is SessionEvent.TextDelta) {
                        textSeen.complete(Unit)
                    }
                }
            }
            textSeen.await()

            session.stop(keepCurrentTurn = false)
            sendJob.await()

            assertEquals(
                listOf(
                    SessionEvent.RoundStarted(input = "hi"),
                    SessionEvent.TextDelta(delta = "partial", fullText = "partial"),
                    SessionEvent.RoundCompleted(
                        fullText = "partial",
                        finishReason = SessionEvent.FinishReason.Stopped
                    )
                ),
                events
            )
            assertEquals(emptyList<ChatTurn>(), session.getHistory())
        } finally {
            neverRelease.complete(Unit)
            session.close()
        }
    }

    @Test
    fun `stop false 在前段已闭合后仍整轮回滚 history`() = runBlocking {
        val tailTextSeen = CompletableDeferred<Unit>()
        val neverRelease = CompletableDeferred<Unit>()
        val events = mutableListOf<SessionEvent>()
        var parseCount = 0
        val protocol = RecordingChatProtocol {
            when (parseCount++) {
                0 -> flowOf(
                    ProtocolEvent.TextDelta("prefix"),
                    ProtocolEvent.ToolCallReady(
                        callId = "call-1",
                        toolName = "lookup",
                        argumentsJson = """{"query":"first"}"""
                    ),
                    ProtocolEvent.Completed
                )

                else -> flow {
                    emit(ProtocolEvent.TextDelta("suffix"))
                    tailTextSeen.complete(Unit)
                    neverRelease.await()
                    emit(ProtocolEvent.Completed)
                }
            }
        }
        val session = newChatSession(protocol = protocol, engine = FakeHttpEngine()) {
            registerLookupTool()
            hooks {
                ok("""{"answer":"ok"}""")
            }
        }

        try {
            withTimeout(3_000) {
                val sendJob = async {
                    session.send("hi") { event ->
                        events += event
                    }
                }
                tailTextSeen.await()

                session.stop(keepCurrentTurn = false)
                sendJob.await()
            }

            assertEquals(
                SessionEvent.RoundCompleted(
                    fullText = "prefixsuffix",
                    finishReason = SessionEvent.FinishReason.Stopped
                ),
                events.last()
            )
            assertEquals(emptyList<ChatTurn>(), session.getHistory())
        } finally {
            neverRelease.complete(Unit)
            session.close()
        }
    }

    @Test
    fun `首事件前 stop 会收口且不挂起`() = runBlocking {
        val streamStarted = CompletableDeferred<Unit>()
        val neverRelease = CompletableDeferred<Unit>()
        val events = mutableListOf<SessionEvent>()
        val protocol = RecordingChatProtocol {
            flow {
                streamStarted.complete(Unit)
                neverRelease.await()
                emit(ProtocolEvent.TextDelta("late"))
            }
        }
        val session = newChatSession(protocol = protocol, engine = FakeHttpEngine())

        try {
            val sendJob = async {
                session.send("hi") { event ->
                    events += event
                }
            }
            streamStarted.await()

            withTimeout(3_000) {
                session.stop()
                sendJob.await()
            }

            assertEquals(
                listOf(
                    SessionEvent.RoundCompleted(
                        fullText = "",
                        finishReason = SessionEvent.FinishReason.Stopped
                    )
                ),
                events
            )
            assertEquals(emptyList<ChatTurn>(), session.getHistory())
        } finally {
            neverRelease.complete(Unit)
            session.close()
        }
    }

    @Test
    fun `onEvent 内调用 stop 不会自锁`() = runBlocking {
        val neverRelease = CompletableDeferred<Unit>()
        val events = mutableListOf<SessionEvent>()
        val protocol = RecordingChatProtocol {
            flow {
                emit(ProtocolEvent.TextDelta("partial"))
                neverRelease.await()
                emit(ProtocolEvent.Completed)
            }
        }
        lateinit var session: ChatSession
        session = newChatSession(protocol = protocol, engine = FakeHttpEngine())

        try {
            withTimeout(3_000) {
                session.send("hi") { event ->
                    events += event
                    if (event is SessionEvent.TextDelta) {
                        session.stop()
                    }
                }
            }

            assertEquals(
                SessionEvent.RoundCompleted(
                    fullText = "partial",
                    finishReason = SessionEvent.FinishReason.Stopped
                ),
                events.last()
            )
            assertEquals(emptyList<ChatTurn>(), session.getHistory())
        } finally {
            neverRelease.complete(Unit)
            session.close()
        }
    }

    @Test
    fun `工具事件回调内调用 stop 不会等待当前工具自身`() = runBlocking {
        val hookRelease = CompletableDeferred<Unit>()
        val events = mutableListOf<SessionEvent>()
        val protocol = hangingToolThenTextProtocol()
        lateinit var session: ChatSession
        session = newChatSession(protocol = protocol, engine = FakeHttpEngine()) {
            registerLookupTool()
            hooks {
                hookRelease.await()
                ok("""{"answer":"late"}""")
            }
        }

        try {
            withTimeout(3_000) {
                session.send("hi") { event ->
                    events += event
                    if (event is SessionEvent.ToolRunning) {
                        session.stop()
                    }
                }
            }

            assertEquals(
                SessionEvent.RoundCompleted(
                    fullText = "",
                    finishReason = SessionEvent.FinishReason.Stopped
                ),
                events.last()
            )
            assertEquals(emptyList<ChatTurn>(), session.getHistory())
        } finally {
            hookRelease.complete(Unit)
            session.close()
        }
    }

    @Test
    fun `stop true 保留 partial assistant`() = runBlocking {
        val textSeen = CompletableDeferred<Unit>()
        val neverRelease = CompletableDeferred<Unit>()
        val events = mutableListOf<SessionEvent>()
        val protocol = RecordingChatProtocol {
            flow {
                emit(ProtocolEvent.TextDelta("partial"))
                neverRelease.await()
                emit(ProtocolEvent.Completed)
            }
        }
        val session = newChatSession(protocol = protocol, engine = FakeHttpEngine())

        try {
            val sendJob = async {
                session.send("hi") { event ->
                    events += event
                    if (event is SessionEvent.TextDelta) {
                        textSeen.complete(Unit)
                    }
                }
            }
            textSeen.await()

            session.stop(keepCurrentTurn = true)
            sendJob.await()

            assertEquals(
                SessionEvent.RoundCompleted(
                    fullText = "partial",
                    finishReason = SessionEvent.FinishReason.Stopped
                ),
                events.last()
            )
            assertEquals(
                listOf(
                    ChatTurn.User(content = "hi"),
                    ChatTurn.Assistant(content = "partial")
                ),
                session.getHistory()
            )
        } finally {
            neverRelease.complete(Unit)
            session.close()
        }
    }

    @Test
    fun `stop true 保留已闭合 tool 链路和安全尾文本 但裁掉尾部未闭合 tool call`() = runBlocking {
        val secondToolStarted = CompletableDeferred<Unit>()
        val secondToolRelease = CompletableDeferred<Unit>()
        val events = mutableListOf<SessionEvent>()
        var parseCount = 0
        val protocol = RecordingChatProtocol {
            when (parseCount++) {
                0 -> flowOf(
                    ProtocolEvent.ToolCallReady(
                        callId = "call-1",
                        toolName = "lookup",
                        argumentsJson = """{"query":"first"}"""
                    ),
                    ProtocolEvent.Completed
                )

                else -> flowOf(
                    ProtocolEvent.TextDelta("tail"),
                    ProtocolEvent.ToolCallReady(
                        callId = "call-2",
                        toolName = "lookup",
                        argumentsJson = """{"query":"second"}"""
                    ),
                    ProtocolEvent.Completed
                )
            }
        }
        lateinit var session: ChatSession
        session = newChatSession(protocol = protocol, engine = FakeHttpEngine()) {
            registerLookupTool()
            hooks {
                when (id) {
                    "call-1" -> ok("""{"answer":"ok-1"}""")
                    "call-2" -> {
                        secondToolStarted.complete(Unit)
                        secondToolRelease.await()
                        ok("""{"answer":"late"}""")
                    }
                    else -> error("unexpected tool call: $id")
                }
            }
        }

        try {
            withTimeout(3_000) {
                val sendJob = async {
                    session.send("hi") { event ->
                        events += event
                    }
                }
                secondToolStarted.await()

                session.stop(keepCurrentTurn = true)
                sendJob.await()
            }

            assertEquals(
                SessionEvent.RoundCompleted(
                    fullText = "tail",
                    finishReason = SessionEvent.FinishReason.Stopped
                ),
                events.last()
            )
            assertEquals(
                listOf(
                    ChatTurn.User(content = "hi"),
                    ChatTurn.Assistant(
                        content = "",
                        toolCalls = listOf(
                            ToolCallSpec(
                                callId = "call-1",
                                toolName = "lookup",
                                argumentsJson = """{"query":"first"}"""
                            )
                        )
                    ),
                    ChatTurn.ToolResult(
                        callId = "call-1",
                        toolName = "lookup",
                        resultJson = """{"answer":"ok-1"}"""
                    ),
                    ChatTurn.Assistant(content = "tail")
                ),
                session.getHistory()
            )
            val partialAssistant = session.getHistory().last() as ChatTurn.Assistant
            assertTrue(partialAssistant.toolCalls.isEmpty())
        } finally {
            secondToolRelease.complete(Unit)
            session.close()
        }
    }

    @Test
    fun `stop true 在多段 assistant 后不重复前文文本`() = runBlocking {
        val tailTextSeen = CompletableDeferred<Unit>()
        val neverRelease = CompletableDeferred<Unit>()
        val events = mutableListOf<SessionEvent>()
        var parseCount = 0
        val protocol = RecordingChatProtocol {
            when (parseCount++) {
                0 -> flowOf(
                    ProtocolEvent.TextDelta("prefix"),
                    ProtocolEvent.ToolCallReady(
                        callId = "call-1",
                        toolName = "lookup",
                        argumentsJson = """{"query":"first"}"""
                    ),
                    ProtocolEvent.Completed
                )

                else -> flow {
                    emit(ProtocolEvent.TextDelta("suffix"))
                    tailTextSeen.complete(Unit)
                    neverRelease.await()
                    emit(ProtocolEvent.Completed)
                }
            }
        }
        val session = newChatSession(protocol = protocol, engine = FakeHttpEngine()) {
            registerLookupTool()
            hooks {
                ok("""{"answer":"ok"}""")
            }
        }

        try {
            withTimeout(3_000) {
                val sendJob = async {
                    session.send("hi") { event ->
                        events += event
                    }
                }
                tailTextSeen.await()

                session.stop(keepCurrentTurn = true)
                sendJob.await()
            }

            val history = session.getHistory()
            val partialAssistant = history.last() as ChatTurn.Assistant

            assertEquals(
                SessionEvent.RoundCompleted(
                    fullText = "prefixsuffix",
                    finishReason = SessionEvent.FinishReason.Stopped
                ),
                events.last()
            )
            assertEquals(
                listOf(
                    ChatTurn.User(content = "hi"),
                    ChatTurn.Assistant(
                        content = "prefix",
                        toolCalls = listOf(
                            ToolCallSpec(
                                callId = "call-1",
                                toolName = "lookup",
                                argumentsJson = """{"query":"first"}"""
                            )
                        )
                    ),
                    ChatTurn.ToolResult(
                        callId = "call-1",
                        toolName = "lookup",
                        resultJson = """{"answer":"ok"}"""
                    ),
                    ChatTurn.Assistant(content = "suffix")
                ),
                history
            )
            assertEquals("suffix", partialAssistant.content)
            assertFalse(partialAssistant.content.contains("prefix"))
        } finally {
            neverRelease.complete(Unit)
            session.close()
        }
    }

    @Test
    fun `idle timeout 首事件前触发 Error 和 IdleTimeout 完成`() = runBlocking {
        val neverRelease = CompletableDeferred<Unit>()
        val protocol = RecordingChatProtocol {
            flow {
                neverRelease.await()
                emit(ProtocolEvent.TextDelta("late"))
            }
        }
        val session = newChatSession(protocol = protocol, engine = FakeHttpEngine()) {
            llmIdleTimeoutSeconds = 1
        }

        try {
            val events = withTimeout(3_000) { session.send("hi").toList() }
            val error = events.filterIsInstance<SessionEvent.Error>().single()
            val completed = events.filterIsInstance<SessionEvent.RoundCompleted>().single()

            assertFalse(events.any { it is SessionEvent.RoundStarted })
            assertEquals(SessionEvent.Stage.Session, error.stage)
            assertEquals("LLM idle timeout: no session event for 1 seconds", error.message)
            assertEquals(SessionEvent.FinishReason.IdleTimeout, completed.finishReason)
            assertEquals("", completed.fullText)
            assertEquals(emptyList<ChatTurn>(), session.getHistory())
        } finally {
            neverRelease.complete(Unit)
            session.close()
        }
    }

    @Test
    fun `idle timeout 回调异常会透传且不会挂起`() = runBlocking {
        val expected = IllegalStateException("timeout callback boom")
        val neverRelease = CompletableDeferred<Unit>()
        val protocol = RecordingChatProtocol {
            flow {
                neverRelease.await()
                emit(ProtocolEvent.TextDelta("late"))
            }
        }
        val session = newChatSession(protocol = protocol, engine = FakeHttpEngine()) {
            llmIdleTimeoutSeconds = 1
        }

        try {
            try {
                withTimeout(3_000) {
                    session.send("hi") { event ->
                        if (event is SessionEvent.Error) {
                            throw expected
                        }
                    }
                }
                fail("send should throw callback exception")
            } catch (actual: Throwable) {
                assertTrue(actual is IllegalStateException)
                assertEquals(expected.message, actual.message)
            }
        } finally {
            neverRelease.complete(Unit)
            session.close()
        }
    }

    @Test
    fun `idle timeout 在工具等待中取消 tool wait`() = runBlocking {
        val hookStarted = CompletableDeferred<Unit>()
        val hookRelease = CompletableDeferred<Unit>()
        val protocol = hangingToolThenTextProtocol()
        val session = newChatSession(protocol = protocol, engine = FakeHttpEngine()) {
            llmIdleTimeoutSeconds = 1
            registerLookupTool()
            hooks {
                hookStarted.complete(Unit)
                hookRelease.await()
                ok("""{"answer":"late"}""")
            }
        }

        try {
            val events = withTimeout(3_000) { session.send("hi").toList() }
            val error = events.filterIsInstance<SessionEvent.Error>().single()
            val completed = events.filterIsInstance<SessionEvent.RoundCompleted>().single()

            assertTrue(hookStarted.isCompleted)
            assertEquals(SessionEvent.Stage.Session, error.stage)
            assertEquals("LLM idle timeout: no session event for 1 seconds", error.message)
            assertEquals(SessionEvent.FinishReason.IdleTimeout, completed.finishReason)
            assertEquals(emptyList<ChatTurn>(), session.getHistory())
        } finally {
            hookRelease.complete(Unit)
            session.close()
        }
    }

    @Test
    fun `idle timeout 为 null 或 0 时关闭`() = runBlocking {
        suspend fun assertDisabled(timeoutSeconds: Long?) {
            val textSeen = CompletableDeferred<Unit>()
            val neverRelease = CompletableDeferred<Unit>()
            val events = mutableListOf<SessionEvent>()
            val protocol = RecordingChatProtocol {
                flow {
                    emit(ProtocolEvent.TextDelta("partial"))
                    neverRelease.await()
                    emit(ProtocolEvent.Completed)
                }
            }
            val session = newChatSession(protocol = protocol, engine = FakeHttpEngine()) {
                llmIdleTimeoutSeconds = timeoutSeconds
            }

            try {
                val sendJob = async {
                    session.send("hi") { event ->
                        events += event
                        if (event is SessionEvent.TextDelta) {
                            textSeen.complete(Unit)
                        }
                    }
                }
                textSeen.await()
                delay(1_100)

                assertFalse(events.any { event ->
                    event is SessionEvent.Error &&
                        event.message.startsWith("LLM idle timeout")
                })
                session.stop()
                sendJob.await()
                assertEquals(
                    SessionEvent.FinishReason.Stopped,
                    events.filterIsInstance<SessionEvent.RoundCompleted>().single().finishReason
                )
            } finally {
                neverRelease.complete(Unit)
                session.close()
            }
        }

        assertDisabled(null)
        assertDisabled(0)
    }

    @Test
    fun `仅工具调用 assistant 是合法 history`() = runBlocking {
        var parseCount = 0
        val protocol = RecordingChatProtocol {
            when (parseCount++) {
                0 -> flowOf(
                    ProtocolEvent.ToolCallReady(
                        callId = "call-1",
                        toolName = "lookup",
                        argumentsJson = """{"query":"hi"}"""
                    ),
                    ProtocolEvent.Completed
                )

                else -> flowOf(
                    ProtocolEvent.TextDelta("done"),
                    ProtocolEvent.Completed
                )
            }
        }
        val session = newChatSession(protocol = protocol, engine = FakeHttpEngine()) {
            localTools {
                add("lookup") {
                    description = "测试工具"
                    string("query") { required = true }
                }
            }
            hooks {
                ok("""{"answer":"ok"}""")
            }
        }

        try {
            val events = session.send("hi").toList()
            val history = session.getHistory()
            val assistantWithTool = history[1] as ChatTurn.Assistant

            assertEquals(
                listOf(
                    ChatTurn.User(content = "hi"),
                    ChatTurn.Assistant(
                        content = "",
                        toolCalls = listOf(
                            ToolCallSpec(
                                callId = "call-1",
                                toolName = "lookup",
                                argumentsJson = """{"query":"hi"}"""
                            )
                        )
                    ),
                    ChatTurn.ToolResult(
                        callId = "call-1",
                        toolName = "lookup",
                        resultJson = """{"answer":"ok"}"""
                    ),
                    ChatTurn.Assistant(content = "done")
                ),
                history
            )
            assertEquals("", assistantWithTool.content)
            assertEquals(
                listOf(
                    ToolCallSpec(
                        callId = "call-1",
                        toolName = "lookup",
                        argumentsJson = """{"query":"hi"}"""
                    )
                ),
                assistantWithTool.toolCalls
            )
            assertEquals(1, events.filterIsInstance<SessionEvent.RoundCompleted>().size)
            assertEquals(SessionEvent.RoundCompleted(fullText = "done"), events.last())
        } finally {
            session.close()
        }
    }

    @Test
    fun `工具调用后第二轮请求写入 tool result 并完成文本响应`() = runBlocking {
        val protocol = singleToolThenTextProtocol(
            toolName = "lookup",
            argumentsJson = """{"query":"hi"}"""
        )
        val session = newChatSession(protocol = protocol, engine = FakeHttpEngine()) {
            registerLookupTool()
            hooks {
                ok("""{"answer":"ok"}""")
            }
        }

        try {
            val events = session.send("hi").toList()

            assertEquals(
                listOf(
                    SessionEvent.RoundStarted(input = "hi"),
                    SessionEvent.ToolRunning(
                        callId = "call-1",
                        toolName = "lookup",
                        kind = ToolCallKind.Local
                    ),
                    SessionEvent.ToolSucceeded(
                        callId = "call-1",
                        toolName = "lookup",
                        kind = ToolCallKind.Local,
                        resultJson = """{"answer":"ok"}"""
                    ),
                    SessionEvent.TextDelta(delta = "done", fullText = "done"),
                    SessionEvent.RoundCompleted(fullText = "done")
                ),
                events
            )
            assertEquals(
                ChatTurn.ToolResult(
                    callId = "call-1",
                    toolName = "lookup",
                    resultJson = """{"answer":"ok"}"""
                ),
                protocol.lastHistory.last()
            )
        } finally {
            session.close()
        }
    }

    @Test
    fun `未知工具发出 ToolFailed 和 Error 且返回 tool error JSON`() = runBlocking {
        val protocol = singleToolThenTextProtocol(toolName = "missing")
        val session = newChatSession(protocol = protocol, engine = FakeHttpEngine())

        try {
            val events = session.send("hi").toList()
            val failed = events.filterIsInstance<SessionEvent.ToolFailed>().single()
            val error = events.filterIsInstance<SessionEvent.Error>().single()
            val toolResult = protocol.lastHistory.last() as ChatTurn.ToolResult

            assertEquals("missing", failed.toolName)
            assertEquals(ToolCallKind.Local, failed.kind)
            assertEquals("Unknown tool 'missing'", failed.message)
            assertEquals(SessionEvent.Stage.Tool, error.stage)
            assertEquals("Unknown tool 'missing'", error.message)
            assertEquals(failed.resultJson, toolResult.resultJson)
            assertTrue(toolResult.resultJson.contains("Unknown tool"))
            assertEquals(
                SessionEvent.RoundCompleted(
                    fullText = "done",
                    finishReason = SessionEvent.FinishReason.Error
                ),
                events.last()
            )
        } finally {
            session.close()
        }
    }

    @Test
    fun `本地工具无 hooks 发出 ToolFailed 和 Error`() = runBlocking {
        val protocol = singleToolThenTextProtocol(toolName = "lookup")
        val session = newChatSession(protocol = protocol, engine = FakeHttpEngine()) {
            registerLookupTool()
        }

        try {
            val events = session.send("hi").toList()
            val failed = events.filterIsInstance<SessionEvent.ToolFailed>().single()
            val error = events.filterIsInstance<SessionEvent.Error>().single()
            val toolResult = protocol.lastHistory.last() as ChatTurn.ToolResult

            assertEquals(
                SessionEvent.ToolRunning(
                    callId = "call-1",
                    toolName = "lookup",
                    kind = ToolCallKind.Local
                ),
                events.filterIsInstance<SessionEvent.ToolRunning>().single()
            )
            assertEquals("No hooks configured", failed.message)
            assertEquals(SessionEvent.Stage.Tool, error.stage)
            assertEquals("no hooks configured", error.message)
            assertTrue(toolResult.resultJson.contains("No hooks configured"))
        } finally {
            session.close()
        }
    }

    @Test
    fun `本地 hook 成功发出 ToolRunning 和 ToolSucceeded 且结果进入下一轮`() = runBlocking {
        val protocol = singleToolThenTextProtocol(toolName = "lookup")
        val session = newChatSession(protocol = protocol, engine = FakeHttpEngine()) {
            registerLookupTool()
            hooks {
                ok("""{"answer":"hook-ok"}""")
            }
        }

        try {
            val events = session.send("hi").toList()

            assertEquals(
                SessionEvent.ToolRunning(
                    callId = "call-1",
                    toolName = "lookup",
                    kind = ToolCallKind.Local
                ),
                events.filterIsInstance<SessionEvent.ToolRunning>().single()
            )
            assertEquals(
                SessionEvent.ToolSucceeded(
                    callId = "call-1",
                    toolName = "lookup",
                    kind = ToolCallKind.Local,
                    resultJson = """{"answer":"hook-ok"}"""
                ),
                events.filterIsInstance<SessionEvent.ToolSucceeded>().single()
            )
            assertEquals(
                ChatTurn.ToolResult(
                    callId = "call-1",
                    toolName = "lookup",
                    resultJson = """{"answer":"hook-ok"}"""
                ),
                protocol.lastHistory.last()
            )
        } finally {
            session.close()
        }
    }

    @Test
    fun `本地 hook 抛异常发出 ToolFailed 和 Error 且 round 收尾`() = runBlocking {
        val boom = IllegalStateException("hook boom")
        val protocol = singleToolThenTextProtocol(toolName = "lookup")
        val session = newChatSession(protocol = protocol, engine = FakeHttpEngine()) {
            registerLookupTool()
            hooks {
                throw boom
            }
        }

        try {
            val events = session.send("hi").toList()
            val errors = events.filterIsInstance<SessionEvent.Error>()

            assertTrue(
                events.filterIsInstance<SessionEvent.ToolFailed>()
                    .any { it.message == "hook boom" && it.toolName == "lookup" }
            )
            assertTrue(
                errors.any {
                    it.stage == SessionEvent.Stage.Tool &&
                        it.message == "hook boom" &&
                        it.cause === boom
                }
            )
            assertEquals(
                SessionEvent.RoundCompleted(
                    fullText = "done",
                    finishReason = SessionEvent.FinishReason.Error
                ),
                events.last()
            )
        } finally {
            session.close()
        }
    }

    @Test
    fun `本地 hook 返回 failure outcome 发出 ToolFailed 和 Error`() = runBlocking {
        val protocol = singleToolThenTextProtocol(toolName = "lookup")
        val session = newChatSession(protocol = protocol, engine = FakeHttpEngine()) {
            registerLookupTool()
            hooks {
                error("hook failed", """{"reason":"bad"}""")
            }
        }

        try {
            val events = session.send("hi").toList()
            val toolResult = protocol.lastHistory.last() as ChatTurn.ToolResult

            assertEquals(
                listOf(
                    SessionEvent.ToolRunning(
                        callId = "call-1",
                        toolName = "lookup",
                        kind = ToolCallKind.Local
                    ),
                    SessionEvent.ToolFailed(
                        callId = "call-1",
                        toolName = "lookup",
                        kind = ToolCallKind.Local,
                        message = "hook failed",
                        resultJson = toolResult.resultJson
                    ),
                    SessionEvent.Error(
                        stage = SessionEvent.Stage.Tool,
                        message = "hook failed"
                    )
                ),
                events.filter {
                    it is SessionEvent.ToolRunning ||
                        it is SessionEvent.ToolFailed ||
                        it is SessionEvent.Error
                }
            )
            assertEquals("lookup", toolResult.toolName)
            assertTrue(toolResult.resultJson.contains("hook failed"))
            assertTrue(toolResult.resultJson.contains("reason"))
            assertTrue(toolResult.resultJson.contains("bad"))
        } finally {
            session.close()
        }
    }

    @Test
    fun `MCP 工具成功发出 ToolSucceeded 且结果进入下一轮`() = runBlocking {
        val engine = FakeMcpHttpEngine(
            toolsByUrl = mapOf("https://mcp.ok" to listOf(remoteSearchTool())),
            toolCallResultsByUrl = mapOf("https://mcp.ok" to """{"answer":"remote-ok"}""")
        )
        val protocol = singleToolThenTextProtocol(
            toolName = "remote_search",
            argumentsJson = """{"query":"hi"}"""
        )
        val session = newChatSession(protocol = protocol, engine = engine) {
            registerDocsMcpServer()
        }

        try {
            session.refreshMcpTools()
            val events = session.send("hi").toList()

            assertTrue(engine.frameCalls.any { it == "https://mcp.ok" to "tools/call" })
            assertEquals(
                SessionEvent.ToolRunning(
                    callId = "call-1",
                    toolName = "remote_search",
                    kind = ToolCallKind.Mcp("docs")
                ),
                events.filterIsInstance<SessionEvent.ToolRunning>().single()
            )
            assertEquals(
                SessionEvent.ToolSucceeded(
                    callId = "call-1",
                    toolName = "remote_search",
                    kind = ToolCallKind.Mcp("docs"),
                    resultJson = """{"answer":"remote-ok"}"""
                ),
                events.filterIsInstance<SessionEvent.ToolSucceeded>().single()
            )
            assertEquals(
                ChatTurn.ToolResult(
                    callId = "call-1",
                    toolName = "remote_search",
                    resultJson = """{"answer":"remote-ok"}"""
                ),
                protocol.lastHistory.last()
            )
        } finally {
            session.close()
        }
    }

    @Test
    fun `MCP 工具失败发出 ToolFailed 和 Error 且返回 tool error JSON`() = runBlocking {
        val engine = FakeMcpHttpEngine(
            toolsByUrl = mapOf("https://mcp.ok" to listOf(remoteSearchTool())),
            toolCallFailuresByUrl = mapOf("https://mcp.ok" to IllegalStateException("mcp boom"))
        )
        val protocol = singleToolThenTextProtocol(
            toolName = "remote_search",
            argumentsJson = """{"query":"hi"}"""
        )
        val session = newChatSession(protocol = protocol, engine = engine) {
            registerDocsMcpServer()
        }

        try {
            session.refreshMcpTools()
            val events = session.send("hi").toList()
            val failed = events.filterIsInstance<SessionEvent.ToolFailed>().single()
            val error = events.filterIsInstance<SessionEvent.Error>().single()
            val toolResult = protocol.lastHistory.last() as ChatTurn.ToolResult

            assertTrue(engine.frameCalls.any { it == "https://mcp.ok" to "tools/call" })
            assertEquals(ToolCallKind.Mcp("docs"), failed.kind)
            assertEquals("remote_search", failed.toolName)
            assertEquals("mcp boom", failed.message)
            assertEquals(toolResult.resultJson, failed.resultJson)
            assertEquals(SessionEvent.Stage.Tool, error.stage)
            assertEquals("mcp boom", error.message)
            assertTrue(toolResult.resultJson.contains("mcp boom"))
        } finally {
            session.close()
        }
    }

    @Test
    fun `MCP server 缺失时 coordinator 发出 ToolFailed 和 Error`() = runBlocking {
        val codec = GsonJsonCodec()
        val coordinator = ToolCallCoordinator(
            mcpClient = object : McpClient {
                override suspend fun call(
                    server: McpServerConfig,
                    toolName: String,
                    argumentsJson: String
                ): String = error("server-null path should not call MCP client")

                override suspend fun listTools(server: McpServerConfig): List<McpDiscoveredTool> {
                    error("server-null path should not list MCP tools")
                }
            },
            codec = codec
        )
        val snapshot = SessionSnapshot(
            endpoint = "https://example.com/chat",
            apiKey = "",
            model = "test-model",
            systemPrompt = null,
            temperature = 0.7f,
            timeouts = HttpTimeouts(connectMs = 1_000, readMs = 1_000, writeMs = 1_000),
            hooksBlock = null,
            appParams = emptyMap(),
            tools = ToolCatalog(
                descriptors = listOf(
                    ToolDescriptor(
                        name = "remote_search",
                        description = "search docs",
                        inputSchema = mapOf("type" to "object"),
                        kind = ToolCallKind.Mcp("docs")
                    )
                )
            ),
            mcpServers = emptyMap(),
            jsonCodec = codec,
            headers = emptyMap(),
            maxTokens = 4096
        )
        val events = mutableListOf<SessionEvent>()

        val message = coordinator.handle(
            toolCall = ToolCallSpec(
                callId = "call-1",
                toolName = "remote_search",
                argumentsJson = """{"query":"hi"}"""
            ),
            snapshot = snapshot
        ) { event ->
            events += event
        }
        val errorJson = codec.decodeMap(message.contentJson).orEmpty()

        assertEquals("call-1", message.callId)
        assertEquals("remote_search", message.toolName)
        assertEquals(
            listOf(
                SessionEvent.ToolRunning(
                    callId = "call-1",
                    toolName = "remote_search",
                    kind = ToolCallKind.Mcp("docs")
                ),
                SessionEvent.ToolFailed(
                    callId = "call-1",
                    toolName = "remote_search",
                    kind = ToolCallKind.Mcp("docs"),
                    message = "MCP server 'docs' is not configured",
                    resultJson = message.contentJson
                ),
                SessionEvent.Error(
                    stage = SessionEvent.Stage.Tool,
                    message = "MCP server 'docs' is not configured"
                )
            ),
            events
        )
        assertEquals("MCP server 'docs' is not configured", errorJson["error"])
    }

    @Test
    fun `多个工具调用结果按调用顺序写入 history 后进入下一轮`() = runBlocking {
        var parseCount = 0
        val protocol = RecordingChatProtocol {
            when (parseCount++) {
                0 -> flowOf(
                    ProtocolEvent.ToolCallReady(
                        callId = "call-1",
                        toolName = "lookup",
                        argumentsJson = """{"query":"one"}"""
                    ),
                    ProtocolEvent.ToolCallReady(
                        callId = "call-2",
                        toolName = "lookup",
                        argumentsJson = """{"query":"two"}"""
                    ),
                    ProtocolEvent.Completed
                )

                else -> flowOf(
                    ProtocolEvent.TextDelta("done"),
                    ProtocolEvent.Completed
                )
            }
        }
        val session = newChatSession(protocol = protocol, engine = FakeHttpEngine()) {
            registerLookupTool()
            hooks {
                when (id) {
                    "call-1" -> ok("""{"answer":"one"}""")
                    "call-2" -> ok("""{"answer":"two"}""")
                    else -> error("unexpected call")
                }
            }
        }

        try {
            val events = session.send("hi").toList()

            assertEquals(
                listOf(
                    ChatTurn.ToolResult(
                        callId = "call-1",
                        toolName = "lookup",
                        resultJson = """{"answer":"one"}"""
                    ),
                    ChatTurn.ToolResult(
                        callId = "call-2",
                        toolName = "lookup",
                        resultJson = """{"answer":"two"}"""
                    )
                ),
                protocol.lastHistory.filterIsInstance<ChatTurn.ToolResult>()
            )
            assertEquals(2, events.filterIsInstance<SessionEvent.ToolSucceeded>().size)
            assertEquals(SessionEvent.RoundCompleted(fullText = "done"), events.last())
        } finally {
            session.close()
        }
    }

    @Test
    fun `多轮工具调用只发送一次 started 和一次 completed`() = runBlocking {
        var parseCount = 0
        val protocol = RecordingChatProtocol {
            when (parseCount++) {
                0 -> flowOf(
                    ProtocolEvent.ToolCallReady(
                        callId = "call-1",
                        toolName = "lookup",
                        argumentsJson = """{"query":"one"}"""
                    ),
                    ProtocolEvent.Completed
                )

                1 -> flowOf(
                    ProtocolEvent.ToolCallReady(
                        callId = "call-2",
                        toolName = "lookup",
                        argumentsJson = """{"query":"two"}"""
                    ),
                    ProtocolEvent.Completed
                )

                else -> flowOf(
                    ProtocolEvent.TextDelta("done"),
                    ProtocolEvent.Completed
                )
            }
        }
        val session = newChatSession(protocol = protocol, engine = FakeHttpEngine()) {
            registerLookupTool()
            hooks {
                when (id) {
                    "call-1" -> ok("""{"answer":"one"}""")
                    "call-2" -> ok("""{"answer":"two"}""")
                    else -> error("unexpected call")
                }
            }
        }

        try {
            val events = session.send("hi").toList()
            val completed = events.filterIsInstance<SessionEvent.RoundCompleted>()

            assertEquals(1, events.filterIsInstance<SessionEvent.RoundStarted>().size)
            assertEquals(1, completed.size)
            assertEquals(SessionEvent.RoundCompleted(fullText = "done"), completed.single())
        } finally {
            session.close()
        }
    }

    @Test
    fun `onEvent 抛普通异常时 send 透传原始异常`() = runBlocking {
        val expected = IllegalStateException("callback boom")
        val protocol = RecordingChatProtocol {
            flowOf(
                ProtocolEvent.TextDelta("hello"),
                ProtocolEvent.Completed
            )
        }
        val session = newChatSession(protocol = protocol, engine = FakeHttpEngine())

        try {
            try {
                session.send("hi") {
                    throw expected
                }
                fail("send should throw callback exception")
            } catch (actual: Throwable) {
                assertSame(expected, actual)
            }
        } finally {
            session.close()
        }
    }

    @Test
    fun `onEvent 抛 CancellationException 时取消语义不被包装`() = runBlocking {
        val expected = CancellationException("callback cancelled")
        val protocol = RecordingChatProtocol {
            flowOf(
                ProtocolEvent.TextDelta("hello"),
                ProtocolEvent.Completed
            )
        }
        val session = newChatSession(protocol = protocol, engine = FakeHttpEngine())

        try {
            try {
                session.send("hi") {
                    throw expected
                }
                fail("send should throw cancellation")
            } catch (actual: CancellationException) {
                assertSame(expected, actual)
            }
        } finally {
            session.close()
        }
    }

    @Test
    fun `resetConversation 清空 history 并取消等待中的工具调用`() = runBlocking {
        val hookStarted = CompletableDeferred<Unit>()
        val hookRelease = CompletableDeferred<Unit>()
        val protocol = hangingToolThenTextProtocol()
        val session = newChatSession(protocol = protocol, engine = FakeHttpEngine()) {
            registerLookupTool()
            hooks {
                hookStarted.complete(Unit)
                hookRelease.await()
                ok("""{"answer":"late"}""")
            }
        }

        try {
            val first = async { session.send("first").toList() }
            hookStarted.await()

            session.resetConversation()
            assertEquals(emptyList<ChatTurn>(), session.getHistory())

            val secondEvents = session.send("second").toList()
            assertEquals(emptyList<ChatTurn>(), protocol.lastHistory)
            assertEquals("second", protocol.lastPendingUserInput)
            assertEquals(
                listOf(
                    ChatTurn.User(content = "second"),
                    ChatTurn.Assistant(content = "done")
                ),
                session.getHistory()
            )
            assertEquals(SessionEvent.RoundCompleted(fullText = "done"), secondEvents.last())
            try {
                first.await()
                fail("first send should be cancelled by resetConversation")
            } catch (_: CancellationException) {
                // 当前任务被 resetConversation 取消是既有收尾语义的一部分。
            }
            Unit
        } finally {
            hookRelease.complete(Unit)
            session.close()
        }
    }

    @Test
    fun `replaceHistory 恢复普通历史后下一轮请求携带 replaced history`() = runBlocking {
        val protocol = RecordingChatProtocol {
            flowOf(ProtocolEvent.TextDelta("next"), ProtocolEvent.Completed)
        }
        val session = newChatSession(protocol = protocol, engine = FakeHttpEngine())
        val restored = listOf(
            ChatTurn.User(content = "hi"),
            ChatTurn.Assistant(content = "hello")
        )

        try {
            session.replaceHistory(restored)
            val events = session.send("continue").toList()

            assertEquals(restored, protocol.lastHistory)
            assertEquals("continue", protocol.lastPendingUserInput)
            assertEquals(SessionEvent.RoundCompleted(fullText = "next"), events.last())
            assertEquals(
                restored + ChatTurn.User(content = "continue") + ChatTurn.Assistant(content = "next"),
                session.getHistory()
            )
        } finally {
            session.close()
        }
    }

    @Test
    fun `replaceHistory 过滤 System 且覆盖不追加`() = runBlocking {
        val protocol = RecordingChatProtocol {
            flowOf(ProtocolEvent.TextDelta("done"), ProtocolEvent.Completed)
        }
        val session = newChatSession(protocol = protocol, engine = FakeHttpEngine())
        val restored = listOf(
            ChatTurn.System(content = "old system"),
            ChatTurn.User(content = "restored")
        )

        try {
            session.send("old").toList()
            assertTrue(session.getHistory().isNotEmpty())

            session.replaceHistory(restored)

            assertEquals(listOf(ChatTurn.User(content = "restored")), session.getHistory())
            session.send("next").toList()
            assertEquals(listOf(ChatTurn.User(content = "restored")), protocol.lastHistory)
        } finally {
            session.close()
        }
    }

    @Test
    fun `replaceHistory 后更新 systemPrompt 使用最新配置`() = runBlocking {
        val protocol = RecordingChatProtocol {
            flowOf(ProtocolEvent.TextDelta("answer"), ProtocolEvent.Completed)
        }
        val session = newChatSession(protocol = protocol, engine = FakeHttpEngine()) {
            systemPrompt = "initial"
        }

        try {
            session.replaceHistory(
                listOf(
                    ChatTurn.System(content = "restored system"),
                    ChatTurn.User(content = "hi")
                )
            )
            session.update {
                systemPrompt = "new"
            }
            session.send("next").toList()

            assertEquals("new", protocol.lastSnapshot?.systemPrompt)
            assertEquals(listOf(ChatTurn.User(content = "hi")), protocol.lastHistory)
        } finally {
            session.close()
        }
    }

    @Test
    fun `replaceHistory 运行中取消当前 round 并清理等待中的工具调用`() = runBlocking {
        val hookStarted = CompletableDeferred<Unit>()
        val hookRelease = CompletableDeferred<Unit>()
        val hookCancelled = CompletableDeferred<Unit>()
        val protocol = hangingToolThenTextProtocol()
        val session = newChatSession(protocol = protocol, engine = FakeHttpEngine()) {
            registerLookupTool()
            hooks {
                try {
                    hookStarted.complete(Unit)
                    hookRelease.await()
                    ok("""{"answer":"late"}""")
                } finally {
                    hookCancelled.complete(Unit)
                }
            }
        }
        val restored = listOf(ChatTurn.User(content = "restored"))

        try {
            val first = async { session.send("first").toList() }
            hookStarted.await()

            session.replaceHistory(restored)
            assertEquals(restored, session.getHistory())
            assertTrue(hookCancelled.isCompleted)

            val secondEvents = session.send("second").toList()
            assertEquals(restored, protocol.lastHistory)
            assertEquals("second", protocol.lastPendingUserInput)
            assertFalse(protocol.lastHistory.any { turn ->
                turn is ChatTurn.ToolResult && turn.callId == "call-1"
            })
            assertEquals(SessionEvent.RoundCompleted(fullText = "done"), secondEvents.last())
            try {
                first.await()
                fail("first send should be cancelled by replaceHistory")
            } catch (_: CancellationException) {
                // replaceHistory 会取消当前 round，并清理等待中的工具调用。
            }
            Unit
        } finally {
            hookRelease.complete(Unit)
            session.close()
        }
    }

    @Test
    fun `replaceHistory 保留 assistant tool calls 和 tool result history`() = runBlocking {
        val protocol = RecordingChatProtocol {
            flowOf(ProtocolEvent.TextDelta("done"), ProtocolEvent.Completed)
        }
        val session = newChatSession(protocol = protocol, engine = FakeHttpEngine())
        val restored = listOf(
            ChatTurn.User(content = "use tool"),
            ChatTurn.Assistant(
                content = "",
                toolCalls = listOf(
                    ToolCallSpec(
                        callId = "call-1",
                        toolName = "lookup",
                        argumentsJson = """{"query":"hi"}"""
                    )
                )
            ),
            ChatTurn.ToolResult(
                callId = "call-1",
                toolName = "lookup",
                resultJson = """{"ok":true}"""
            )
        )

        try {
            session.replaceHistory(restored)
            session.send("next").toList()

            assertEquals(restored, protocol.lastHistory)
            val assistant = protocol.lastHistory.filterIsInstance<ChatTurn.Assistant>().single()
            val toolResult = protocol.lastHistory.filterIsInstance<ChatTurn.ToolResult>().single()
            assertEquals("call-1", assistant.toolCalls.single().callId)
            assertEquals("lookup", assistant.toolCalls.single().toolName)
            assertEquals("""{"query":"hi"}""", assistant.toolCalls.single().argumentsJson)
            assertEquals("call-1", toolResult.callId)
            assertEquals("lookup", toolResult.toolName)
            assertEquals("""{"ok":true}""", toolResult.resultJson)
        } finally {
            session.close()
        }
    }

    @Test
    fun `replaceHistory getHistory 对称恢复`() = runBlocking {
        val firstProtocol = RecordingChatProtocol {
            flowOf(ProtocolEvent.TextDelta("hello"), ProtocolEvent.Completed)
        }
        val firstSession = newChatSession(protocol = firstProtocol, engine = FakeHttpEngine())
        val secondProtocol = RecordingChatProtocol {
            flowOf(ProtocolEvent.TextDelta("continued"), ProtocolEvent.Completed)
        }
        val secondSession = newChatSession(protocol = secondProtocol, engine = FakeHttpEngine())

        try {
            firstSession.send("hi").toList()
            val restored = firstSession.getHistory()

            secondSession.replaceHistory(restored)
            secondSession.send("next").toList()

            assertEquals(restored, secondProtocol.lastHistory)
            assertEquals(restored, secondSession.getHistory().take(restored.size))
        } finally {
            firstSession.close()
            secondSession.close()
        }
    }

    @Test
    fun `连续 send 取消前一轮且不留下孤儿 tool call`() = runBlocking {
        val hookStarted = CompletableDeferred<Unit>()
        val hookRelease = CompletableDeferred<Unit>()
        val protocol = hangingToolThenTextProtocol()
        val session = newChatSession(protocol = protocol, engine = FakeHttpEngine()) {
            registerLookupTool()
            hooks {
                hookStarted.complete(Unit)
                hookRelease.await()
                ok("""{"answer":"late"}""")
            }
        }

        try {
            val first = async { session.send("first").toList() }
            hookStarted.await()

            val secondEvents = session.send("second").toList()

            assertFalse(protocol.lastHistory.any { turn ->
                turn is ChatTurn.ToolResult && turn.callId == "call-1"
            })
            assertEquals("second", protocol.lastPendingUserInput)
            assertFalse(session.getHistory().any { turn ->
                turn is ChatTurn.ToolResult && turn.callId == "call-1"
            })
            assertEquals(SessionEvent.RoundCompleted(fullText = "done"), secondEvents.last())
            try {
                first.await()
                fail("first send should be cancelled by the next send")
            } catch (_: CancellationException) {
                // 后一轮 send 会取消前一轮挂起工作。
            }
            Unit
        } finally {
            hookRelease.complete(Unit)
            session.close()
        }
    }

    @Test
    fun `close 会关闭 engine`() = runBlocking {
        val engine = FakeHttpEngine()
        val protocol = RecordingChatProtocol {
            flowOf(ProtocolEvent.TextDelta("hello"), ProtocolEvent.Completed)
        }
        val session = newChatSession(protocol = protocol, engine = engine)

        session.close()

        assertTrue(engine.closed)
    }

    @Test
    fun `update 后普通 config 字段会被后续 send snapshot 使用`() = runBlocking {
        val engine = FakeHttpEngine()
        val protocol = RecordingChatProtocol {
            flowOf(ProtocolEvent.TextDelta("hello"), ProtocolEvent.Completed)
        }
        val session = newChatSession(protocol = protocol, engine = engine) {
            endpoint = "https://example.com/initial"
            model = "initial-model"
            header("X-Trace", "initial")
        }

        try {
            session.update {
                endpoint = "https://example.com/updated"
                model = "updated-model"
                header("X-Trace", "updated")
                header("X-New", "new")
            }
            session.send("hi").toList()

            val snapshot = checkNotNull(protocol.lastSnapshot)
            assertEquals("https://example.com/updated", snapshot.endpoint)
            assertEquals("updated-model", snapshot.model)
            assertEquals(
                mapOf(
                    "X-Trace" to "updated",
                    "X-New" to "new"
                ),
                snapshot.headers
            )
            assertEquals("updated", engine.frameRequests.single().headers["X-Trace"])
            assertEquals("new", engine.frameRequests.single().headers["X-New"])
        } finally {
            session.close()
        }
    }

    @Test
    fun `send 期间 update 不影响当前 round snapshot`() = runBlocking {
        val firstRoundStarted = CompletableDeferred<Unit>()
        val firstRoundRelease = CompletableDeferred<Unit>()
        var parseCount = 0
        val protocol = RecordingChatProtocol {
            flow {
                parseCount += 1
                if (parseCount == 1) {
                    firstRoundStarted.complete(Unit)
                    firstRoundRelease.await()
                }
                emit(ProtocolEvent.TextDelta("hello"))
                emit(ProtocolEvent.Completed)
            }
        }
        val session = newChatSession(protocol = protocol, engine = FakeHttpEngine()) {
            model = "initial-model"
        }

        try {
            val first = async { session.send("first").toList() }
            firstRoundStarted.await()
            val firstSnapshot = checkNotNull(protocol.lastSnapshot)

            session.update {
                model = "updated-model"
            }
            firstRoundRelease.complete(Unit)
            first.await()

            assertEquals("initial-model", firstSnapshot.model)

            session.send("second").toList()
            assertEquals("updated-model", protocol.lastSnapshot?.model)
        } finally {
            firstRoundRelease.complete(Unit)
            session.close()
        }
    }

    @Test
    fun `close 取消 scope 使挂起 send 被取消`() = runBlocking {
        val roundStarted = CompletableDeferred<Unit>()
        val neverRelease = CompletableDeferred<Unit>()
        val protocol = RecordingChatProtocol {
            flow {
                roundStarted.complete(Unit)
                neverRelease.await()
                emit(ProtocolEvent.TextDelta("late"))
                emit(ProtocolEvent.Completed)
            }
        }
        val session = newChatSession(protocol = protocol, engine = FakeHttpEngine())

        val sendJob = async { session.send("hi").toList() }
        roundStarted.await()

        session.close()

        try {
            sendJob.await()
            fail("send should be cancelled by close")
        } catch (_: CancellationException) {
            // close 会取消 session scope，挂起中的 round 必须随之取消。
        } finally {
            neverRelease.complete(Unit)
        }
        Unit
    }

    @Test
    fun `custom header 按大小写不敏感规则覆盖 auth header`() = runBlocking {
        val engine = FakeHttpEngine()
        val protocol = RecordingChatProtocol {
            flowOf(ProtocolEvent.TextDelta("hello"), ProtocolEvent.Completed)
        }
        protocol.apiKeyHeaders = mapOf("Authorization" to "Bearer auth", "X-Trace" to "auth-trace")
        val session = newChatSession(protocol = protocol, engine = engine) {
            apiKey = "test-key"
            header("authorization", "Bearer custom")
            header("X-Custom", "custom")
        }

        try {
            session.send("hi").toList()
            val headers = engine.frameRequests.single().headers

            assertFalse(headers.containsKey("Authorization"))
            assertEquals("Bearer custom", headers["authorization"])
            assertEquals("auth-trace", headers["X-Trace"])
            assertEquals("custom", headers["X-Custom"])
        } finally {
            session.close()
        }
    }

    @Test
    fun `update 忽略 jsonCodec 和 httpEngine open only 字段`() = runBlocking {
        val initialEngine = FakeHttpEngine()
        val updatedEngine = FakeHttpEngine()
        val initialCodec = GsonJsonCodec()
        val protocol = singleToolThenTextProtocol(toolName = "missing")
        val session = newChatSession(protocol = protocol, engine = initialEngine) {
            jsonCodec = initialCodec
        }

        try {
            session.update {
                jsonCodec = throwingJsonCodec()
                httpEngine = updatedEngine
            }
            session.send("hi").toList()

            assertEquals(2, initialEngine.frameRequests.size)
            assertEquals(0, updatedEngine.frameRequests.size)
            assertSame(initialCodec, protocol.lastSnapshot?.jsonCodec)
            assertTrue(
                protocol.lastHistory
                    .filterIsInstance<ChatTurn.ToolResult>()
                    .single()
                    .resultJson
                    .contains("Unknown tool")
            )
        } finally {
            session.close()
        }
    }

    private fun singleToolThenTextProtocol(
        toolName: String,
        argumentsJson: String = """{}"""
    ): RecordingChatProtocol {
        var parseCount = 0
        return RecordingChatProtocol {
            when (parseCount++) {
                0 -> flowOf(
                    ProtocolEvent.ToolCallReady(
                        callId = "call-1",
                        toolName = toolName,
                        argumentsJson = argumentsJson
                    ),
                    ProtocolEvent.Completed
                )

                else -> flowOf(
                    ProtocolEvent.TextDelta("done"),
                    ProtocolEvent.Completed
                )
            }
        }
    }

    private fun hangingToolThenTextProtocol(): RecordingChatProtocol {
        var parseCount = 0
        return RecordingChatProtocol {
            when (parseCount++) {
                0 -> flowOf(
                    ProtocolEvent.ToolCallReady(
                        callId = "call-1",
                        toolName = "lookup",
                        argumentsJson = """{"query":"first"}"""
                    ),
                    ProtocolEvent.Completed
                )

                else -> flowOf(
                    ProtocolEvent.TextDelta("done"),
                    ProtocolEvent.Completed
                )
            }
        }
    }

    private fun SessionConfig.registerLookupTool() {
        localTools {
            add("lookup") {
                description = "测试工具"
                string("query") { required = false }
            }
        }
    }

    private fun SessionConfig.registerDocsMcpServer() {
        mcp {
            add("docs") {
                http { url = "https://mcp.ok" }
            }
        }
    }

    private fun remoteSearchTool(): McpDiscoveredTool {
        return McpDiscoveredTool(
            name = "remote_search",
            description = "search docs",
            inputSchema = mapOf("type" to "object")
        )
    }

    private fun throwingJsonCodec(): JsonCodec {
        return object : JsonCodec {
            override fun encode(value: Any?): String = error("updated codec should be ignored")

            override fun <T : Any> decode(json: String, type: Class<T>): T? {
                error("updated codec should be ignored")
            }

            override fun decodeMap(json: String): Map<String, Any?>? {
                error("updated codec should be ignored")
            }

            override fun decodeList(json: String): List<Any?>? {
                error("updated codec should be ignored")
            }
        }
    }
}
