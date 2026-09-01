package com.niki914.okia.loop

import com.niki914.okia.error.LLMErrorCode
import com.niki914.okia.error.RetryPolicy
import com.niki914.okia.event.TurnEvent
import com.niki914.okia.fake.FakeHttpEngine
import com.niki914.okia.fake.FakeProtocolMapper
import com.niki914.okia.hooks.Hooks
import com.niki914.okia.hooks.HttpRequestHolder
import com.niki914.okia.hooks.InputHolder
import com.niki914.okia.hooks.SerializationHolder
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.okia.protocol.ProtocolEvent
import com.niki914.okia.protocol.RequestSnapshot
import com.niki914.okia.tooling.DefaultToolRegistry
import com.niki914.okia.transport.HttpRequest
import com.niki914.okia.transport.HttpTimeouts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hooks 时机接入测试（T5）：Input / Serialization / Request 三对时机在
 * RealAgentLoop 主流程中的调用点、改写落点、链式分发与异常策略。
 * 断言公开面可观察行为（hook 收到的参数、协议/传输收到的改写值、终态），
 * 不依赖实现内部结构。
 */
class RealAgentLoopHooksTest {

    // ── fixtures ───────────────────────────────────────────────────────────

    private fun user(text: String) = Message.User(listOf(ContentBlock.Text(text)))

    private fun textOfUser(message: Message): String =
        ((message as Message.User).content.single() as ContentBlock.Text).text

    private fun completed() =
        ProtocolEvent.Completed(stopReason = com.niki914.okia.message.StopReason.Stop)

    private fun loopRequest(
        events: List<ProtocolEvent>,
        hooks: List<Hooks> = emptyList(),
        history: List<Message> = listOf(user("hi")),
        input: String = "hi",
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
        input = input,
        options = LoopOptions(),
        idleTimeoutSeconds = null,
        toolRegistry = DefaultToolRegistry(),
        protocolMapper = FakeProtocolMapper(events),
        hooks = hooks,
        httpEngine = engine,
        retryPolicy = RetryPolicy(),
        onCommit = onCommit
    )

    private suspend fun runLoop(
        request: LoopRequest,
        emitted: MutableList<TurnEvent> = mutableListOf()
    ): TurnResult = RealAgentLoop().run(request) { emitted += it }

    /** 只记录各时机调用序列的 hook（tag 区分链中身份）。 */
    private class RecordingHooks(
        private val calls: MutableList<String>,
        private val tag: String = "h"
    ) : Hooks {
        override suspend fun beforeInput(input: InputHolder) {
            calls += "$tag:beforeInput"
        }

        override suspend fun afterInput(input: InputHolder) {
            calls += "$tag:afterInput"
        }

        override suspend fun beforeSerialization(request: SerializationHolder) {
            calls += "$tag:beforeSerialization"
        }

        override suspend fun afterSerialization(
            request: SerializationHolder,
            httpRequest: HttpRequest
        ) {
            calls += "$tag:afterSerialization"
        }

        override suspend fun beforeRequest(request: HttpRequestHolder) {
            calls += "$tag:beforeRequest"
        }

        override suspend fun afterRequest(request: HttpRequest) {
            calls += "$tag:afterRequest"
        }
    }

    // ── 时机触发与顺序 ─────────────────────────────────────────────────────

    @Test
    fun allSixTimingsFireInOrder() = runTest {
        val calls = mutableListOf<String>()
        runLoop(loopRequest(listOf(completed()), listOf(RecordingHooks(calls))))

        assertEquals(
            listOf(
                "h:beforeInput", "h:afterInput",
                "h:beforeSerialization", "h:afterSerialization",
                "h:beforeRequest", "h:afterRequest"
            ),
            calls
        )
    }

    @Test
    fun emptyHooksListChangesNothing() = runTest {
        val result = runLoop(loopRequest(listOf(completed())))
        assertEquals(TurnResult.Completed(CompletionReason.Stop), result)
    }

    @Test
    fun chainRunsInRegistrationOrder() = runTest {
        val calls = mutableListOf<String>()
        runLoop(
            loopRequest(
                listOf(completed()),
                listOf(RecordingHooks(calls, "a"), RecordingHooks(calls, "b"))
            )
        )

        assertEquals(
            listOf(
                "a:beforeInput", "b:beforeInput",
                "a:afterInput", "b:afterInput",
                "a:beforeSerialization", "b:beforeSerialization",
                "a:afterSerialization", "b:afterSerialization",
                "a:beforeRequest", "b:beforeRequest",
                "a:afterRequest", "b:afterRequest"
            ),
            calls
        )
    }

    // ── Input 时机：改写落点 = 请求历史投影（树不变，事件保持原文） ─────

    @Test
    fun beforeInputReceivesOriginalInput() = runTest {
        val seen = mutableListOf<String>()
        val hooks = listOf(object : Hooks {
            override suspend fun beforeInput(input: InputHolder) {
                seen += input.text
            }
        })
        runLoop(loopRequest(listOf(completed()), hooks))
        assertEquals(listOf("hi"), seen)
    }

    @Test
    fun beforeInputRewriteReachesBuildRequestAndEventKeepsOriginal() = runTest {
        val mapper = FakeProtocolMapper(listOf(completed()))
        val emitted = mutableListOf<TurnEvent>()
        val hooks = listOf(object : Hooks {
            override suspend fun beforeInput(input: InputHolder) {
                input.write("rewritten", "h1")
            }
        })

        val result =
            runLoop(loopRequest(listOf(completed()), hooks).copy(protocolMapper = mapper), emitted)

        assertEquals(TurnResult.Completed(CompletionReason.Stop), result)
        // 落点：buildRequest 收到改写版历史（模型看到改写文本）
        assertEquals("rewritten", textOfUser(mapper.builtHistories.single().last()))
        // 事件仍发原始 input（事件反映事实，与树一致）
        assertEquals(TurnEvent.TurnStarted("hi"), emitted.first())
    }

    @Test
    fun beforeInputWithoutRewritePassesOriginalHistory() = runTest {
        val mapper = FakeProtocolMapper(listOf(completed()))
        runLoop(
            loopRequest(listOf(completed()), listOf(RecordingHooks(mutableListOf()))).copy(
                protocolMapper = mapper
            )
        )
        assertEquals("hi", textOfUser(mapper.builtHistories.single().last()))
    }

    @Test
    fun afterInputSeesRewrittenValueAndWriter() = runTest {
        val seen = mutableListOf<Pair<String, String?>>()
        val hooks = listOf(object : Hooks {
            override suspend fun beforeInput(input: InputHolder) {
                input.write("rewritten", "h1")
            }

            override suspend fun afterInput(input: InputHolder) {
                seen += input.text to input.lastWriter
            }
        })
        runLoop(loopRequest(listOf(completed()), hooks))
        assertEquals(listOf("rewritten" to "h1"), seen)
    }

    // ── Serialization 时机：改写 → buildRequest 输入 ─────────────────────

    @Test
    fun beforeSerializationHistoryRewriteReachesBuildRequest() = runTest {
        val mapper = FakeProtocolMapper(listOf(completed()))
        val hooks = listOf(object : Hooks {
            override suspend fun beforeSerialization(request: SerializationHolder) {
                request.write(request.snapshot, listOf(user("rewritten")), "h1")
            }
        })
        runLoop(loopRequest(listOf(completed()), hooks).copy(protocolMapper = mapper))
        assertEquals("rewritten", textOfUser(mapper.builtHistories.single().last()))
    }

    @Test
    fun beforeSerializationSnapshotRewriteReachesBuildRequest() = runTest {
        val mapper = FakeProtocolMapper(listOf(completed()))
        val hooks = listOf(object : Hooks {
            override suspend fun beforeSerialization(request: SerializationHolder) {
                request.write(
                    request.snapshot.copy(endpoint = "https://redacted.test/v1"),
                    request.history,
                    "h1"
                )
            }
        })
        runLoop(loopRequest(listOf(completed()), hooks).copy(protocolMapper = mapper))
        assertEquals("https://redacted.test/v1", mapper.builtRequests.single().url)
    }

    @Test
    fun afterSerializationReceivesBuiltRequest() = runTest {
        val seen = mutableListOf<String>()
        val hooks = listOf(object : Hooks {
            override suspend fun afterSerialization(
                request: SerializationHolder,
                httpRequest: HttpRequest
            ) {
                seen += httpRequest.url
            }
        })
        runLoop(loopRequest(listOf(completed()), hooks))
        assertEquals(listOf("https://api.test/v1"), seen)
    }

    @Test
    fun earlierSerializationMutationVisibleToLaterHook() = runTest {
        val seenByB = mutableListOf<String>()
        val hooks = listOf(
            object : Hooks {
                override suspend fun beforeSerialization(request: SerializationHolder) {
                    request.write(request.snapshot, listOf(user("from-a")), "a")
                }
            },
            object : Hooks {
                override suspend fun beforeSerialization(request: SerializationHolder) {
                    seenByB += textOfUser(request.history.single())
                }
            }
        )
        runLoop(loopRequest(listOf(completed()), hooks))
        assertEquals(listOf("from-a"), seenByB)
    }

    // ── Request 时机：改写 → HttpEngine.stream 输入；after 只读实际请求 ──

    @Test
    fun beforeRequestRewriteReachesEngine() = runTest {
        val engine = FakeHttpEngine()
        val hooks = listOf(object : Hooks {
            override suspend fun beforeRequest(request: HttpRequestHolder) {
                request.write(request.request.copy(url = "https://redacted.test/v1"), "h1")
            }
        })
        runLoop(loopRequest(listOf(completed()), hooks, engine = engine))
        assertEquals("https://redacted.test/v1", engine.streamedRequests.single().url)
    }

    @Test
    fun afterRequestReceivesActuallySentRequest() = runTest {
        val engine = FakeHttpEngine()
        val seen = mutableListOf<String>()
        val hooks = listOf(
            object : Hooks {
                override suspend fun beforeRequest(request: HttpRequestHolder) {
                    request.write(request.request.copy(url = "https://redacted.test/v1"), "h1")
                }
            },
            object : Hooks {
                override suspend fun afterRequest(request: HttpRequest) {
                    seen += request.url
                }
            }
        )
        runLoop(loopRequest(listOf(completed()), hooks, engine = engine))
        assertEquals(listOf("https://redacted.test/v1"), seen)
    }

    // ── 异常策略：模型段 hook 异常 → 回合 Failed（HookFailed）；取消传播 ──

    @Test
    fun beforeInputFailureFailsTurn() = runTest {
        val hooks = listOf(object : Hooks {
            override suspend fun beforeInput(input: InputHolder) {
                throw RuntimeException("boom")
            }
        })
        val result = runLoop(loopRequest(listOf(completed()), hooks))
        assertEquals(LLMErrorCode.HookFailed, (result as TurnResult.Failed).error.code)
    }

    @Test
    fun beforeSerializationFailureFailsTurn() = runTest {
        val hooks = listOf(object : Hooks {
            override suspend fun beforeSerialization(request: SerializationHolder) {
                throw RuntimeException("boom")
            }
        })
        val result = runLoop(loopRequest(listOf(completed()), hooks))
        assertEquals(LLMErrorCode.HookFailed, (result as TurnResult.Failed).error.code)
    }

    @Test
    fun afterSerializationFailureFailsTurn() = runTest {
        val hooks = listOf(object : Hooks {
            override suspend fun afterSerialization(
                request: SerializationHolder,
                httpRequest: HttpRequest
            ) {
                throw RuntimeException("boom")
            }
        })
        val result = runLoop(loopRequest(listOf(completed()), hooks))
        assertEquals(LLMErrorCode.HookFailed, (result as TurnResult.Failed).error.code)
    }

    @Test
    fun beforeRequestFailureFailsTurn() = runTest {
        val hooks = listOf(object : Hooks {
            override suspend fun beforeRequest(request: HttpRequestHolder) {
                throw RuntimeException("boom")
            }
        })
        val result = runLoop(loopRequest(listOf(completed()), hooks))
        assertEquals(LLMErrorCode.HookFailed, (result as TurnResult.Failed).error.code)
    }

    @Test
    fun afterRequestFailureFailsTurn() = runTest {
        val hooks = listOf(object : Hooks {
            override suspend fun afterRequest(request: HttpRequest) {
                throw RuntimeException("boom")
            }
        })
        val result = runLoop(loopRequest(listOf(completed()), hooks))
        assertEquals(LLMErrorCode.HookFailed, (result as TurnResult.Failed).error.code)
    }

    @Test
    fun hookFailureEmitsTurnFailed() = runTest {
        val emitted = mutableListOf<TurnEvent>()
        val hooks = listOf(object : Hooks {
            override suspend fun beforeSerialization(request: SerializationHolder) {
                throw RuntimeException("boom")
            }
        })
        runLoop(loopRequest(listOf(completed()), hooks), emitted)
        assertTrue(emitted.any { it is TurnEvent.TurnFailed })
    }

    @Test
    fun hookFailureAfterStreamFailureDoesNotMaskTransportError() = runTest {
        // stream 失败（Transport）在 beforeRequest 之后：beforeRequest 成功、
        // stream 抛网络错误 → Transport；afterRequest 不触发（请求未完成）
        val engine = FakeHttpEngine().apply { streamError = RuntimeException("network down") }
        val afterRequestCalls = mutableListOf<String>()
        val hooks = listOf(object : Hooks {
            override suspend fun afterRequest(request: HttpRequest) {
                afterRequestCalls += request.url
            }
        })
        val result = runLoop(loopRequest(listOf(completed()), hooks, engine = engine))
        assertEquals(LLMErrorCode.Transport, (result as TurnResult.Failed).error.code)
        assertTrue(afterRequestCalls.isEmpty())
    }

    @Test
    fun hookCancellationPropagates() = runTest {
        val hooks = listOf(object : Hooks {
            override suspend fun beforeInput(input: InputHolder) {
                throw CancellationException("hook cancelled")
            }
        })
        var caught: CancellationException? = null
        try {
            runLoop(loopRequest(listOf(completed()), hooks))
        } catch (e: CancellationException) {
            caught = e
        }
        assertTrue(caught != null)
    }

    // ── 时机与主流程交错（完整序列） ─────────────────────────────────────

    @Test
    fun hooksInterleaveWithEventsInOrder() = runTest {
        val calls = mutableListOf<String>()
        val emitted = mutableListOf<TurnEvent>()
        val hooks = listOf(RecordingHooks(calls))
        runLoop(loopRequest(listOf(completed()), hooks), emitted)

        // TurnStarted（事件）→ beforeInput → afterInput → beforeSerialization
        // → afterSerialization → beforeRequest → afterRequest → 流事件
        assertEquals(TurnEvent.TurnStarted("hi"), emitted[0])
        assertEquals("h:beforeInput", calls[0])
        assertEquals("h:beforeSerialization", calls[2])
        assertEquals("h:beforeRequest", calls[4])
        assertEquals(TurnEvent.TurnCompleted::class, emitted.last()::class)
    }
}
