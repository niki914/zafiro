package com.niki914.okia

import com.niki914.okia.conversation.JsonSessionCodec
import com.niki914.okia.event.StopCause
import com.niki914.okia.conversation.SessionSnapshot
import com.niki914.okia.event.TurnEvent
import com.niki914.okia.fake.FakeAgentLoop
import com.niki914.okia.fake.FakeHttpEngine
import com.niki914.okia.fake.FakeProtocolMapper
import com.niki914.okia.fake.StubMcpClient
import com.niki914.okia.loop.AgentLoop
import com.niki914.okia.loop.CompletionReason
import com.niki914.okia.loop.LoopRequest
import com.niki914.okia.loop.RealAgentLoop
import com.niki914.okia.loop.TurnResult
import com.niki914.okia.message.AssistantMessage
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.okia.message.StopReason
import com.niki914.okia.protocol.ProtocolCompatMapper
import com.niki914.okia.protocol.ProtocolEvent
import com.niki914.okia.transport.HttpEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RealOkiaTest {

    // ── fixtures ───────────────────────────────────────────────────────────

    private fun user(text: String) = Message.User(listOf(ContentBlock.Text(text)))

    private fun textOf(message: AssistantMessage): String =
        (message.content.single() as ContentBlock.Text).text

    private fun completed(stopReason: StopReason? = StopReason.Stop) =
        ProtocolEvent.Completed(stopReason = stopReason)

    private fun deps(
        mapper: ProtocolCompatMapper,
        loop: AgentLoop = RealAgentLoop(),
        engine: HttpEngine = FakeHttpEngine()
    ) = object : OkiaDependencies {
        override val agentLoop = loop
        override val protocolMapper = mapper
        override val mcpClient = StubMcpClient
    }

    private fun openOkia(
        mapper: ProtocolCompatMapper,
        engine: HttpEngine = FakeHttpEngine(),
        loop: AgentLoop = RealAgentLoop(),
        restore: SessionSnapshot? = null,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        config: OkiaConfig.Builder.() -> Unit = {}
    ): RealOkia = RealOkia(
        dependencies = deps(mapper, loop, engine),
        restore = restore,
        initialConfig = OkiaConfig.Builder().apply {
            endpoint = "https://api.test/v1"
            apiKey = "test-key"
            model = "test-model"
            httpEngine = engine
        }.apply(config).build(),
        turnScope = scope
    )

    private fun testScope(scheduler: kotlinx.coroutines.test.TestCoroutineScheduler): CoroutineScope =
        CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))

    // ── 初始状态 ───────────────────────────────────────────────────────────

    @Test
    fun initialStateEmpty() = runTest {
        val okia = openOkia(FakeProtocolMapper(emptyList<ProtocolEvent>()))
        val snapshot = okia.conversation.value
        assertNull(snapshot.leafId)
        assertTrue(snapshot.history.isEmpty())
        assertNull(snapshot.live)
        okia.close()
    }

    // ── send 正常路径 ──────────────────────────────────────────────────────

    @Test
    fun sendCompletedCommitsUserAndAssistant() = runTest {
        val okia = openOkia(
            FakeProtocolMapper(listOf(ProtocolEvent.TextDelta("hello"), completed())),
            scope = testScope(testScheduler)
        )
        val result = okia.send("hi") { }

        assertEquals(TurnResult.Completed(CompletionReason.Stop), result)
        val history = okia.conversation.value.history
        assertEquals(2, history.size)
        assertEquals(user("hi"), history[0].message)
        val assistant = history[1].message as Message.Assistant
        assertEquals("hello", textOf(assistant.message))
        assertNull(okia.conversation.value.live)
        okia.close()
    }

    @Test
    fun sendEmitsFullEventSequence() = runTest {
        val okia = openOkia(
            FakeProtocolMapper(listOf(ProtocolEvent.TextDelta("hel"), ProtocolEvent.TextDelta("lo"), completed())),
            scope = testScope(testScheduler)
        )
        val emitted = mutableListOf<TurnEvent>()
        okia.send("hi") { emitted += it }

        assertEquals(TurnEvent.TurnStarted("hi"), emitted[0])
        assertEquals(0, (emitted[1] as TurnEvent.TextStarted).index)
        assertEquals("lo", (emitted[2] as TurnEvent.TextDelta).delta)
        assertTrue(emitted[4] is TurnEvent.TurnCompleted)
        assertEquals(5, emitted.size)
        okia.close()
    }

    @Test
    fun eventsFlowDeliversEvents() = runTest {
        val okia = openOkia(
            FakeProtocolMapper(listOf(ProtocolEvent.TextDelta("hel"), ProtocolEvent.TextDelta("lo"), completed())),
            scope = testScope(testScheduler)
        )
        val received = mutableListOf<TurnEvent>()
        val collector = launch { okia.events.collect { received += it } }
        runCurrent()

        okia.send("hi") { }

        assertEquals(5, received.size)
        assertTrue(received.last() is TurnEvent.TurnCompleted)
        collector.cancel()
        okia.close()
    }

    @Test
    fun liveUpdatesDuringStreamingAndClearsOnCommit() = runTest {
        val events = MutableSharedFlow<ProtocolEvent>(extraBufferCapacity = 16)
        val okia = openOkia(FakeProtocolMapper(events), scope = testScope(testScheduler))
        val sendJob = launch { okia.send("hi") { } }
        runCurrent()

        events.emit(ProtocolEvent.TextDelta("hel"))
        runCurrent()
        assertEquals("hel", textOf(okia.conversation.value.live!!))
        assertEquals(1, okia.conversation.value.history.size) // 流式期间 history 不提前更新

        events.emit(ProtocolEvent.TextDelta("lo"))
        runCurrent()
        assertEquals("hello", textOf(okia.conversation.value.live!!))

        events.emit(completed())
        runCurrent()
        assertNull(okia.conversation.value.live)
        assertEquals(2, okia.conversation.value.history.size)

        sendJob.join()
        okia.close()
    }

    // ── send 失败路径 ──────────────────────────────────────────────────────

    @Test
    fun sendFailedWithoutPartialKeepsUserOnly() = runTest {
        val okia = openOkia(
            FakeProtocolMapper(listOf(ProtocolEvent.Error(RuntimeException("boom")))),
            scope = testScope(testScheduler)
        )
        val result = okia.send("hi") { }

        assertTrue(result is TurnResult.Failed)
        assertEquals(1, okia.conversation.value.history.size)
        assertEquals(user("hi"), okia.conversation.value.history[0].message)
        assertNull(okia.conversation.value.live)
        okia.close()
    }

    @Test
    fun sendFailedWithPartialCommitsPartial() = runTest {
        val okia = openOkia(
            FakeProtocolMapper(listOf(ProtocolEvent.TextDelta("par"), ProtocolEvent.Error(RuntimeException("boom")))),
            scope = testScope(testScheduler)
        )
        val result = okia.send("hi") { }

        assertTrue(result is TurnResult.Failed)
        val history = okia.conversation.value.history
        assertEquals(2, history.size)
        assertEquals("par", textOf((history[1].message as Message.Assistant).message))
        assertNull(okia.conversation.value.live)
        okia.close()
    }

    // ── 并发契约 ───────────────────────────────────────────────────────────

    @Test
    fun concurrentSendThrows() = runTest {
        val events = MutableSharedFlow<ProtocolEvent>(extraBufferCapacity = 16)
        val okia = openOkia(FakeProtocolMapper(events), scope = testScope(testScheduler))
        val first = launch { okia.send("one") { } }
        runCurrent()

        val second = try {
            okia.send("two") { }
            null
        } catch (e: IllegalStateException) {
            e
        }
        assertNotNull(second)

        first.cancel()
        runCurrent() // 让 turn job 清理 activeTurn
        okia.close()
    }

    @Test
    fun mutationDuringActiveTurnThrows() = runTest {
        val events = MutableSharedFlow<ProtocolEvent>(extraBufferCapacity = 16)
        val okia = openOkia(FakeProtocolMapper(events), scope = testScope(testScheduler))
        val first = launch { okia.send("one") { } }
        runCurrent()

        assertNotNull(try { okia.rewind("x"); null } catch (e: IllegalStateException) { e })
        assertNotNull(try { okia.update { }; null } catch (e: IllegalStateException) { e })
        assertNotNull(try { okia.export(); null } catch (e: IllegalStateException) { e })
        assertNotNull(try { okia.close(); null } catch (e: IllegalStateException) { e })

        first.cancel()
        runCurrent() // 让 turn job 清理 activeTurn
        okia.close()
    }

    @Test
    fun serialSendsWork() = runTest {
        val okia = openOkia(
            FakeProtocolMapper(listOf(completed())),
            scope = testScope(testScheduler)
        )
        assertEquals(TurnResult.Completed(CompletionReason.Stop), okia.send("one") { })
        assertEquals(TurnResult.Completed(CompletionReason.Stop), okia.send("two") { })
        assertEquals(4, okia.conversation.value.history.size)
        okia.close()
    }

    // ── stop 契约 ──────────────────────────────────────────────────────────

    @Test
    fun stopAbortsAndCommitsPartial() = runTest {
        val events = MutableSharedFlow<ProtocolEvent>(extraBufferCapacity = 16)
        val okia = openOkia(FakeProtocolMapper(events), scope = testScope(testScheduler))
        val result = async { okia.send("hi") { } }
        runCurrent()

        events.emit(ProtocolEvent.TextDelta("par"))
        runCurrent()
        okia.stop()

        assertEquals(TurnResult.Aborted(StopCause.UserStop), result.await())
        val history = okia.conversation.value.history
        assertEquals(2, history.size)
        assertEquals("par", textOf((history[1].message as Message.Assistant).message))
        assertNull(okia.conversation.value.live)
        okia.close()
    }

    @Test
    fun stopWithoutActiveTurnIsNoop() = runTest {
        val okia = openOkia(FakeProtocolMapper(emptyList<ProtocolEvent>()), scope = testScope(testScheduler))
        okia.stop()
        okia.close()
    }

    @Test
    fun stopThenImmediateSendWorks() = runTest {
        val events = MutableSharedFlow<ProtocolEvent>(extraBufferCapacity = 16)
        val okia = openOkia(FakeProtocolMapper(events), scope = testScope(testScheduler))

        val first = async { okia.send("one") { } }
        runCurrent()
        okia.stop()
        assertEquals(TurnResult.Aborted(StopCause.UserStop), first.await())

        // stop 返回后立即再 send：activeTurn 已清空，不抛
        val second = async { okia.send("two") { } }
        runCurrent()
        events.emit(completed())
        runCurrent()
        assertEquals(TurnResult.Completed(CompletionReason.Stop), second.await())
        okia.close()
    }

    @Test
    fun externalCancellationPropagates() = runTest {
        val events = MutableSharedFlow<ProtocolEvent>(extraBufferCapacity = 16)
        val okia = openOkia(FakeProtocolMapper(events), scope = testScope(testScheduler))
        val job = launch { okia.send("hi") { } }
        runCurrent()

        job.cancel()
        runCurrent()

        assertTrue(job.isCancelled)
        okia.close()
    }

    // ── 会话操作 ───────────────────────────────────────────────────────────

    @Test
    fun rewindMovesLeafAndValidatesEntry() = runTest {
        val okia = openOkia(
            FakeProtocolMapper(listOf(completed())),
            scope = testScope(testScheduler)
        )
        okia.send("hi") { }
        val userEntry = okia.conversation.value.history[0]

        okia.rewind(userEntry.id)
        assertEquals(listOf(user("hi")), okia.conversation.value.history.map { it.message })

        assertNotNull(try { okia.rewind("missing"); null } catch (e: IllegalArgumentException) { e })
        okia.close()
    }

    @Test
    fun exportRestoreRoundTrip() = runTest {
        val okia = openOkia(
            FakeProtocolMapper(listOf(ProtocolEvent.TextDelta("hello"), completed())),
            scope = testScope(testScheduler)
        )
        okia.send("hi") { }
        val snapshot = okia.export()

        val raw = JsonSessionCodec().encode(snapshot)
        val decoded = JsonSessionCodec().decode(raw)

        val restored = openOkia(
            FakeProtocolMapper(emptyList<ProtocolEvent>()),
            restore = decoded,
            scope = testScope(testScheduler)
        )
        assertEquals(okia.conversation.value.history, restored.conversation.value.history)
        assertEquals(okia.conversation.value.leafId, restored.conversation.value.leafId)

        okia.close()
        restored.close()
    }

    @Test
    fun updateReplacesConfigSnapshot() = runTest {
        val okia = openOkia(FakeProtocolMapper(emptyList<ProtocolEvent>()), scope = testScope(testScheduler))
        val before = okia.config()
        val hook = object : com.niki914.okia.hooks.Hooks {}

        okia.update {
            model = "new-model"
            hooks = listOf(hook)
        }
        val after = okia.config()

        assertEquals("new-model", after.model)
        assertEquals(listOf<Any>(hook), after.hooks)
        assertEquals(before.endpoint, after.endpoint)
        assertEquals(before.apiKey, after.apiKey)
        okia.close()
    }

    @Test
    fun closeThenAllOperationsThrow() = runTest {
        val okia = openOkia(FakeProtocolMapper(emptyList<ProtocolEvent>()), scope = testScope(testScheduler))
        okia.close()

        assertNotNull(try { okia.send("hi") { }; null } catch (e: IllegalStateException) { e })
        assertNotNull(try { okia.rewind("x"); null } catch (e: IllegalStateException) { e })
        assertNotNull(try { okia.update { }; null } catch (e: IllegalStateException) { e })
        assertNotNull(try { okia.export(); null } catch (e: IllegalStateException) { e })
        assertNotNull(try { okia.close(); null } catch (e: IllegalStateException) { e })
    }

    @Test
    fun agentLoopReceivesRequestWithHistoryAndOptions() = runTest {
        var received: LoopRequest? = null
        val loop = FakeAgentLoop { request, _ ->
            received = request
            TurnResult.Completed(CompletionReason.Stop)
        }
        val okia = openOkia(
            FakeProtocolMapper(emptyList<ProtocolEvent>()),
            loop = loop,
            scope = testScope(testScheduler)
        )
        okia.send("hi", TurnOptions(systemPrompt = "sys", model = "override-model")) { }

        val request = received!!
        assertEquals("hi", request.input)
        assertEquals("override-model", request.snapshot.model)
        assertEquals("sys", request.snapshot.systemPrompt)
        assertEquals(listOf(user("hi")), request.history) // history 已含当前输入
        okia.close()
    }
}
