package com.niki914.okia.loop

import com.niki914.okia.event.TurnEvent
import com.niki914.okia.fake.FakeHttpEngine
import com.niki914.okia.fake.FakeProtocolMapper
import com.niki914.okia.fake.RecordingToolExecutor
import com.niki914.okia.fake.localTool
import com.niki914.okia.hooks.Hooks
import com.niki914.okia.hooks.InputHolder
import com.niki914.okia.hooks.ToolCallHolder
import com.niki914.okia.hooks.ToolResultHolder
import com.niki914.okia.message.AssistantMessage
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.okia.message.StopReason
import com.niki914.okia.message.ToolCallOutcome
import com.niki914.okia.protocol.AnthropicMessagesProtocol
import com.niki914.okia.protocol.ProtocolEvent
import com.niki914.okia.protocol.RequestSnapshot
import com.niki914.okia.tooling.DefaultToolRegistry
import com.niki914.okia.tooling.ToolRegistry
import com.niki914.okia.transport.HttpRequest
import com.niki914.okia.transport.HttpTimeouts
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具执行段落测试（T6）：beforeToolCall 改写 / 阻断 / 短路、afterToolCall
 * 结果替换、工具段 hook 异常 → Failure outcome、outcome 5 态事件映射、
 * thinking 多块累积、input 改写作用域回归。断言公开面可观察行为。
 */
class RealAgentLoopToolingTest {

    // ── fixtures ───────────────────────────────────────────────────────────

    private fun user(text: String) = Message.User(listOf(ContentBlock.Text(text)))

    private fun loopRequest(
        events: List<ProtocolEvent>,
        toolRegistry: ToolRegistry = DefaultToolRegistry(),
        hooks: List<Hooks> = emptyList(),
        input: String = "hi",
        history: List<Message> = listOf(user("hi")),
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
        toolRegistry = toolRegistry,
        protocolMapper = FakeProtocolMapper(events),
        hooks = hooks,
        httpEngine = FakeHttpEngine(),
        retryPolicy = com.niki914.okia.error.RetryPolicy(),
        onCommit = onCommit
    )

    private suspend fun runLoop(
        request: LoopRequest,
        emitted: MutableList<TurnEvent> = mutableListOf()
    ): TurnResult = RealAgentLoop().run(request) { emitted += it }

    /** 一次工具往返（ToolUse → 执行 → 空流结尾触发 Parse 失败，但工具段落已跑完）。 */
    private fun toolTurn(events: List<ProtocolEvent>): LoopRequest = loopRequest(events)

    private fun toolResultOf(message: Message): Message.ToolResult = message as Message.ToolResult

    // ── beforeToolCall：改写 / 阻断 / 短路 ────────────────────────────────

    @Test
    fun beforeToolCallRewriteArgumentsReachesExecutor() = runTest {
        val executor = RecordingToolExecutor()
        val registry = DefaultToolRegistry().apply { register(localTool("tool"), executor) }
        val hooks = listOf(object : Hooks {
            override suspend fun beforeToolCall(call: ToolCallHolder) {
                call.write("{\"q\":\"rewritten\"}", "h1")
            }
        })
        val mapper = FakeProtocolMapper(
            listOf(
                listOf(
                    ProtocolEvent.ToolCallReady("call1", "tool", "{\"q\":1}"),
                    ProtocolEvent.Completed(stopReason = StopReason.ToolUse)
                ),
                listOf(ProtocolEvent.Completed(stopReason = StopReason.Stop))
            )
        )

        runLoop(loopRequest(emptyList(), toolRegistry = registry, hooks = hooks).copy(protocolMapper = mapper))

        // executor 收到改写参数；lastWriter 记录
        assertEquals("{\"q\":\"rewritten\"}", executor.calls.single().argumentsJson)
    }

    @Test
    fun toolCallReadyWithoutDeltaCarriesFinalArgumentsToExecutor() = runTest {
        // 契约回归：ToolCallReady 携带最终参数（ProtocolEvent 契约）；只有 Ready、
        // 无 Started/Delta 的协议（完整响应 API）执行器必须收到完整参数而非空串。
        val executor = RecordingToolExecutor()
        val registry = DefaultToolRegistry().apply { register(localTool("tool"), executor) }
        val mapper = FakeProtocolMapper(
            listOf(
                listOf(
                    ProtocolEvent.ToolCallReady("call1", "tool", "{\"q\":1,\"k\":\"v\"}"),
                    ProtocolEvent.Completed(stopReason = StopReason.ToolUse)
                ),
                listOf(ProtocolEvent.Completed(stopReason = StopReason.Stop))
            )
        )

        runLoop(loopRequest(emptyList(), toolRegistry = registry).copy(protocolMapper = mapper))

        // Ready 携带的最终参数是事实源（此前实现用累积 delta，Ready-only 场景收到空串）
        assertEquals("{\"q\":1,\"k\":\"v\"}", executor.calls.single().argumentsJson)
    }

    @Test
    fun beforeToolCallWriteOutcomeBlocksExecutionAndStopsLaterHooks() = runTest {
        val executor = RecordingToolExecutor()
        val registry = DefaultToolRegistry().apply { register(localTool("tool"), executor) }
        val calls = mutableListOf<String>()
        val hooks = listOf(
            object : Hooks {
                override suspend fun beforeToolCall(call: ToolCallHolder) {
                    calls += "h1"
                    call.writeOutcome(ToolCallOutcome.Intercepted("blocked", "cached", false), "h1")
                }
            },
            object : Hooks {
                override suspend fun beforeToolCall(call: ToolCallHolder) { calls += "h2" }
            }
        )
        val mapper = FakeProtocolMapper(
            listOf(
                listOf(
                    ProtocolEvent.ToolCallReady("call1", "tool", "{}"),
                    ProtocolEvent.Completed(stopReason = StopReason.ToolUse)
                ),
                listOf(ProtocolEvent.Completed(stopReason = StopReason.Stop))
            )
        )

        runLoop(loopRequest(emptyList(), toolRegistry = registry, hooks = hooks).copy(protocolMapper = mapper))

        // 阻断：executor 不执行；后续 hook 不执行；拦截结果回喂模型
        assertTrue(executor.calls.isEmpty())
        assertEquals(listOf("h1"), calls)
        val toolResult = mapper.builtHistories.last().last() as Message.ToolResult
        assertEquals(
            ToolCallOutcome.Intercepted("blocked", "cached", false),
            toolResult.outcome
        )
    }

    @Test
    fun blockedToolSkipsAfterToolCall() = runTest {
        val executor = RecordingToolExecutor()
        val registry = DefaultToolRegistry().apply { register(localTool("tool"), executor) }
        var afterCalls = 0
        val hooks = listOf(object : Hooks {
            override suspend fun beforeToolCall(call: ToolCallHolder) {
                call.writeOutcome(ToolCallOutcome.Intercepted("blocked"), "h1")
            }
            override suspend fun afterToolCall(call: ToolCallHolder, result: ToolResultHolder) { afterCalls++ }
        })
        val mapper = FakeProtocolMapper(
            listOf(
                listOf(
                    ProtocolEvent.ToolCallReady("call1", "tool", "{}"),
                    ProtocolEvent.Completed(stopReason = StopReason.ToolUse)
                ),
                listOf(ProtocolEvent.Completed(stopReason = StopReason.Stop))
            )
        )

        runLoop(loopRequest(emptyList(), toolRegistry = registry, hooks = hooks).copy(protocolMapper = mapper))

        assertEquals(0, afterCalls)
    }

    @Test
    fun beforeToolCallExceptionBecomesFailureOutcomeAndTurnContinues() = runTest {
        val executor = RecordingToolExecutor()
        val registry = DefaultToolRegistry().apply { register(localTool("tool"), executor) }
        val hooks = listOf(object : Hooks {
            override suspend fun beforeToolCall(call: ToolCallHolder) {
                throw IllegalStateException("hook bug")
            }
        })
        val mapper = FakeProtocolMapper(
            listOf(
                listOf(
                    ProtocolEvent.ToolCallReady("call1", "tool", "{}"),
                    ProtocolEvent.Completed(stopReason = StopReason.ToolUse)
                ),
                listOf(ProtocolEvent.Completed(stopReason = StopReason.Stop))
            )
        )

        val result = runLoop(loopRequest(emptyList(), toolRegistry = registry, hooks = hooks).copy(protocolMapper = mapper))

        // 回合不失败：该工具以 Failure outcome 回喂（§8.4 #13），回合继续到 Stop
        assertEquals(TurnResult.Completed(CompletionReason.Stop), result)
        assertTrue(executor.calls.isEmpty())
        val toolResult = mapper.builtHistories.last().last() as Message.ToolResult
        val failure = toolResult.outcome as ToolCallOutcome.Failure
        assertTrue(failure.message.contains("beforeToolCall hook failed"))
    }

    // ── afterToolCall：结果替换 / 异常 ────────────────────────────────────

    @Test
    fun afterToolCallRewriteOutcomeReachesEncode() = runTest {
        val executor = RecordingToolExecutor().apply { outcome = ToolCallOutcome.Success("original") }
        val registry = DefaultToolRegistry().apply { register(localTool("tool"), executor) }
        val hooks = listOf(object : Hooks {
            override suspend fun afterToolCall(call: ToolCallHolder, result: ToolResultHolder) {
                result.write(ToolCallOutcome.Success("replaced"), "h1")
            }
        })
        val mapper = FakeProtocolMapper(
            listOf(
                listOf(
                    ProtocolEvent.ToolCallReady("call1", "tool", "{}"),
                    ProtocolEvent.Completed(stopReason = StopReason.ToolUse)
                ),
                listOf(ProtocolEvent.Completed(stopReason = StopReason.Stop))
            )
        )

        runLoop(loopRequest(emptyList(), toolRegistry = registry, hooks = hooks).copy(protocolMapper = mapper))

        val toolResult = mapper.builtHistories.last().last() as Message.ToolResult
        assertEquals(ToolCallOutcome.Success("replaced"), toolResult.outcome)
    }

    @Test
    fun afterToolCallExceptionBecomesFailureOutcome() = runTest {
        val executor = RecordingToolExecutor().apply { outcome = ToolCallOutcome.Success("ok") }
        val registry = DefaultToolRegistry().apply { register(localTool("tool"), executor) }
        val hooks = listOf(object : Hooks {
            override suspend fun afterToolCall(call: ToolCallHolder, result: ToolResultHolder) {
                throw IllegalStateException("after bug")
            }
        })
        val mapper = FakeProtocolMapper(
            listOf(
                listOf(
                    ProtocolEvent.ToolCallReady("call1", "tool", "{}"),
                    ProtocolEvent.Completed(stopReason = StopReason.ToolUse)
                ),
                listOf(ProtocolEvent.Completed(stopReason = StopReason.Stop))
            )
        )

        val result = runLoop(loopRequest(emptyList(), toolRegistry = registry, hooks = hooks).copy(protocolMapper = mapper))

        assertEquals(TurnResult.Completed(CompletionReason.Stop), result)
        val toolResult = mapper.builtHistories.last().last() as Message.ToolResult
        assertTrue((toolResult.outcome as ToolCallOutcome.Failure).message.contains("afterToolCall hook failed"))
    }

    // ── outcome 5 态 → 事件映射 ───────────────────────────────────────────

    private suspend fun runWithOutcome(outcome: ToolCallOutcome): List<TurnEvent> {
        val executor = RecordingToolExecutor().apply { this.outcome = outcome }
        val registry = DefaultToolRegistry().apply { register(localTool("tool"), executor) }
        val mapper = FakeProtocolMapper(
            listOf(
                listOf(
                    ProtocolEvent.ToolCallReady("call1", "tool", "{}"),
                    ProtocolEvent.Completed(stopReason = StopReason.ToolUse)
                ),
                listOf(ProtocolEvent.Completed(stopReason = StopReason.Stop))
            )
        )
        val emitted = mutableListOf<TurnEvent>()
        runLoop(loopRequest(emptyList(), toolRegistry = registry).copy(protocolMapper = mapper), emitted)
        return emitted
    }

    @Test
    fun successMapsToToolSucceeded() = runTest {
        val emitted = runWithOutcome(ToolCallOutcome.Success("x"))
        assertEquals(1, emitted.filterIsInstance<TurnEvent.ToolSucceeded>().size)
        assertTrue(emitted.none { it is TurnEvent.ToolFailed })
    }

    @Test
    fun failureMapsToToolFailed() = runTest {
        val emitted = runWithOutcome(ToolCallOutcome.Failure("nope"))
        assertEquals(1, emitted.filterIsInstance<TurnEvent.ToolFailed>().size)
        assertTrue(emitted.none { it is TurnEvent.ToolSucceeded })
    }

    @Test
    fun interceptedIsErrorFalseMapsToSucceeded() = runTest {
        val emitted = runWithOutcome(ToolCallOutcome.Intercepted("cached", "data", false))
        assertEquals(1, emitted.filterIsInstance<TurnEvent.ToolSucceeded>().size)
    }

    @Test
    fun interceptedIsErrorTrueMapsToFailed() = runTest {
        val emitted = runWithOutcome(ToolCallOutcome.Intercepted("denied", null, true))
        assertEquals(1, emitted.filterIsInstance<TurnEvent.ToolFailed>().size)
    }

    @Test
    fun interruptedAndUnknownMapToFailed() = runTest {
        val interrupted = runWithOutcome(ToolCallOutcome.Interrupted("partial"))
        assertEquals(1, interrupted.filterIsInstance<TurnEvent.ToolFailed>().size)
        val unknown = runWithOutcome(ToolCallOutcome.Unknown("state unknown"))
        assertEquals(1, unknown.filterIsInstance<TurnEvent.ToolFailed>().size)
    }

    @Test
    fun toolEventsCarryFullOutcome() = runTest {
        val emitted = runWithOutcome(ToolCallOutcome.Failure("nope", "detail"))
        val failed = emitted.filterIsInstance<TurnEvent.ToolFailed>().single()
        assertEquals("nope", (failed.outcome as ToolCallOutcome.Failure).message)
        assertEquals("detail", (failed.outcome as ToolCallOutcome.Failure).content)
    }

    // ── thinking 多块 ──────────────────────────────────────────────────────

    @Test
    fun thinkingBlocksEmitStartedDeltaEndedAndMixedContent() = runTest {
        val commits = mutableListOf<List<Message>>()
        val emitted = mutableListOf<TurnEvent>()
        val mapper = FakeProtocolMapper(
            listOf(
                ProtocolEvent.ThinkingDelta("re"),
                ProtocolEvent.ThinkingDelta("ason"),
                ProtocolEvent.TextDelta("ans"),
                ProtocolEvent.TextDelta("wer"),
                ProtocolEvent.Completed(stopReason = StopReason.Stop)
            )
        )

        runLoop(loopRequest(emptyList()) { commits += it }.copy(protocolMapper = mapper), emitted)

        val types = emitted.map { it::class.simpleName!!.removePrefix("TurnEvent\$") }
        assertEquals(
            listOf(
                "TurnStarted", "ThinkingStarted", "ThinkingDelta",
                "ThinkingEnded", "TextStarted", "TextDelta", "TextEnded", "TurnCompleted"
            ),
            types
        )
        // 最终消息 content = [Thinking, Text]（块顺序保持）
        val assistant = (commits.single().single() as Message.Assistant).message
        assertEquals(
            listOf(ContentBlock.Thinking("reason"), ContentBlock.Text("answer")),
            assistant.content
        )
        // ThinkingEnded 携带完整思考
        val ended = emitted.filterIsInstance<TurnEvent.ThinkingEnded>().single()
        assertEquals("reason", ended.content)
    }

    @Test
    fun alternatingThinkingTextBlocksFlushWithoutResidue() = runTest {
        // 回归（评审发现）：flush 只复位 started 标志、未清空 builder。thinking/text
        // 交替多次时旧内容 append 进新块（Text("AC") 应为 Text("C")），事件与
        // 最终消息都携带累计残留。正常多 block response（Gemini parts 交替 / Anthropic
        // 多 thinking 块）即可触发，非并发竞态。
        val commits = mutableListOf<List<Message>>()
        val emitted = mutableListOf<TurnEvent>()
        val mapper = FakeProtocolMapper(
            listOf(
                ProtocolEvent.ThinkingDelta("think-1"),
                ProtocolEvent.TextDelta("answer-1"),
                ProtocolEvent.ThinkingDelta("think-2"),
                ProtocolEvent.TextDelta("answer-2"),
                ProtocolEvent.Completed(stopReason = StopReason.Stop)
            )
        )

        runLoop(loopRequest(emptyList()) { commits += it }.copy(protocolMapper = mapper), emitted)

        // 严格断言四个块分别为 think-1 / answer-1 / think-2 / answer-2
        val assistant = (commits.single().single() as Message.Assistant).message
        assertEquals(
            listOf(
                ContentBlock.Thinking("think-1"),
                ContentBlock.Text("answer-1"),
                ContentBlock.Thinking("think-2"),
                ContentBlock.Text("answer-2")
            ),
            assistant.content
        )
        // ThinkingEnded / TextEnded 携带各块完整内容（非累计残留）
        assertEquals(
            listOf("think-1", "think-2"),
            emitted.filterIsInstance<TurnEvent.ThinkingEnded>().map { it.content }
        )
        assertEquals(
            listOf("answer-1", "answer-2"),
            emitted.filterIsInstance<TurnEvent.TextEnded>().map { it.content }
        )
    }

    @Test
    fun interleavedToolCallKeepsProviderBlockOrder() = runTest {
        // CR5 回归：ToolCallReady 到达时先 flush 进行中 thinking/text 再插入统一
        // blocks，块序保持 provider 原始交错（Anthropic interleaved thinking / Gemini
        // thought+functionCall）。旧实现双容器拼接把 tool call 推到最后、前后 thinking
        // 被合并、事件 index 与最终消息位置漂移。
        val executor = RecordingToolExecutor()
        val registry = DefaultToolRegistry().apply { register(localTool("tool"), executor) }
        val commits = mutableListOf<List<Message>>()
        val emitted = mutableListOf<TurnEvent>()
        val mapper = FakeProtocolMapper(
            listOf(
                listOf(
                    ProtocolEvent.ThinkingDelta("think-a"),
                    ProtocolEvent.ToolCallStarted("call1", "tool"),
                    ProtocolEvent.ToolCallDelta("call1", "tool", """{"q":1}"""),
                    ProtocolEvent.ToolCallReady("call1", "tool", """{"q":1}"""),
                    ProtocolEvent.ThinkingDelta("think-b"),
                    ProtocolEvent.TextDelta("answer"),
                    ProtocolEvent.Completed(stopReason = StopReason.ToolUse)
                ),
                listOf(
                    ProtocolEvent.TextDelta("done"),
                    ProtocolEvent.Completed(stopReason = StopReason.Stop)
                )
            )
        )

        runLoop(
            loopRequest(emptyList(), toolRegistry = registry) { commits += it }.copy(protocolMapper = mapper),
            emitted
        )

        // 最终消息块序 = provider 原始交错 [Thinking, ToolCall, Thinking, Text]，
        // tool call 不被推到最后、前后 thinking 不合并
        val assistant1 = commits[0].single() as Message.Assistant
        assertEquals(
            listOf(
                ContentBlock.Thinking("think-a"),
                ContentBlock.ToolCall("call1", "tool", """{"q":1}"""),
                ContentBlock.Thinking("think-b"),
                ContentBlock.Text("answer")
            ),
            assistant1.message.content
        )
        // ToolCallReady 事件 index = 最终消息中的终值位置，不再随后续 flush 漂移
        val ready = emitted.filterIsInstance<TurnEvent.ToolCallReady>().single()
        assertEquals(1, ready.index)
        // 交错的 thinking 各自成块；第一个 ThinkingEnded 先于 ToolCallReady 发出
        assertEquals(
            listOf("think-a", "think-b"),
            emitted.filterIsInstance<TurnEvent.ThinkingEnded>().map { it.content }
        )
        val thinkingEndedIndex = emitted.indexOfFirst { it is TurnEvent.ThinkingEnded && it.content == "think-a" }
        val toolCallReadyIndex = emitted.indexOfFirst { it is TurnEvent.ToolCallReady }
        assertTrue(thinkingEndedIndex < toolCallReadyIndex)
    }

    @Test
    fun thinkingSignatureRecordedOnFinalMessage() = runTest {
        val commits = mutableListOf<List<Message>>()
        val mapper = FakeProtocolMapper(
            listOf(
                ProtocolEvent.ThinkingDelta("reason"),
                ProtocolEvent.ThinkingSignature("sig-123"),
                ProtocolEvent.Completed(stopReason = StopReason.Stop)
            )
        )

        runLoop(loopRequest(emptyList()) { commits += it }.copy(protocolMapper = mapper))

        val assistant = (commits.single().single() as Message.Assistant).message
        assertEquals("sig-123", assistant.reasoningSignature)
    }

    @Test
    fun thinkingSignatureCarriedOnThinkingBlock() = runTest {
        // 评审发现（Anthropic thinking 签名）：协议发出 ThinkingSignature 后，
        // 最终消息的 Thinking 块应携带签名（Anthropic 序列化检查 block.signature，
        // 无签名思考回放转文本）。当前 flushThinking 只创建 ContentBlock.Thinking(text)，
        // 签名只落在消息级 reasoningSignature —— 块级签名丢失。
        val commits = mutableListOf<List<Message>>()
        val mapper = FakeProtocolMapper(
            listOf(
                ProtocolEvent.ThinkingDelta("reason"),
                ProtocolEvent.ThinkingSignature("sig-123"),
                ProtocolEvent.Completed(stopReason = StopReason.Stop)
            )
        )

        runLoop(loopRequest(emptyList()) { commits += it }.copy(protocolMapper = mapper))

        val assistant = (commits.single().single() as Message.Assistant).message
        val thinking = assistant.content.single() as ContentBlock.Thinking
        assertEquals("sig-123", thinking.signature)
    }

    @Test
    fun anthropicReplayPreservesThinkingBlockWithSignature() = runTest {
        // 完整链路回归：loop 收 thinking + 签名 → 消息进历史 → Anthropic 序列化
        // 应输出 type=thinking 块（带 signature 原样回带）。当前签名在 loop 边界
        // 丢失 → 序列化按无签名思考转文本（type=text），与 PR 描述不符。
        val commits = mutableListOf<List<Message>>()
        val mapper = FakeProtocolMapper(
            listOf(
                ProtocolEvent.ThinkingDelta("reason"),
                ProtocolEvent.ThinkingSignature("sig-123"),
                ProtocolEvent.Completed(stopReason = StopReason.Stop)
            )
        )
        runLoop(loopRequest(emptyList()) { commits += it }.copy(protocolMapper = mapper))
        val assistantMessage = (commits.single().single() as Message.Assistant).message

        val request = AnthropicMessagesProtocol().buildRequest(
            RequestSnapshot(
                endpoint = "https://api.anthropic.com/v1/messages",
                apiKey = "sk-test",
                model = "claude-sonnet-4",
                systemPrompt = null,
                temperature = 0.7f,
                maxTokens = 100,
                headers = emptyMap(),
                timeouts = HttpTimeouts(1_000, 1_000, 1_000),
                tools = emptyList()
            ),
            listOf(Message.Assistant(assistantMessage))
        )
        val content = Json.parseToJsonElement(request.body!!).jsonObject["messages"]!!.jsonArray[0]
            .jsonObject["content"]!!.jsonArray
        val thinkingBlock = content.firstOrNull {
            it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "thinking"
        }
        assertNotNull("thinking 块应保留（当前被转成 text 块）", thinkingBlock)
        assertEquals("sig-123", thinkingBlock!!.jsonObject["signature"]!!.jsonPrimitive.content)
        assertEquals("reason", thinkingBlock.jsonObject["thinking"]!!.jsonPrimitive.content)
    }

    @Test
    fun opaquePayloadAttachedToThinkingBlock() = runTest {
        // 协议私有 payload（OpenAI reasoning envelope）：loop 只挂载不解析，
        // flush 时写入 Thinking 块 opaquePayload。
        val commits = mutableListOf<List<Message>>()
        val mapper = FakeProtocolMapper(
            listOf(
                ProtocolEvent.ThinkingOpaquePayload("openai-responses:reasoning:v1:{\"items\":[{}]}"),
                ProtocolEvent.ThinkingDelta("reason"),
                ProtocolEvent.Completed(stopReason = StopReason.Stop)
            )
        )

        runLoop(loopRequest(emptyList()) { commits += it }.copy(protocolMapper = mapper))

        val assistant = (commits.single().single() as Message.Assistant).message
        val thinking = assistant.content.single() as ContentBlock.Thinking
        assertEquals("reason", thinking.text)
        assertEquals("openai-responses:reasoning:v1:{\"items\":[{}]}", thinking.opaquePayload)
    }

    @Test
    fun opaquePayloadOnlyStillCommitsThinkingBlock() = runTest {
        // payload-only：没有思考文本 delta 也须落 Thinking 块（官方 reasoning
        // summary 需显式启用，不能要求先出现 ThinkingDelta）。
        val commits = mutableListOf<List<Message>>()
        val mapper = FakeProtocolMapper(
            listOf(
                ProtocolEvent.ThinkingOpaquePayload("openai-responses:reasoning:v1:{\"items\":[{}]}"),
                ProtocolEvent.Completed(stopReason = StopReason.Stop)
            )
        )

        runLoop(loopRequest(emptyList()) { commits += it }.copy(protocolMapper = mapper))

        val assistant = (commits.single().single() as Message.Assistant).message
        val thinking = assistant.content.single() as ContentBlock.Thinking
        assertEquals("", thinking.text)
        assertEquals("openai-responses:reasoning:v1:{\"items\":[{}]}", thinking.opaquePayload)
    }

    @Test
    fun textAndToolCallMixedMessageCommittedWhole() = runTest {
        val executor = RecordingToolExecutor()
        val registry = DefaultToolRegistry().apply { register(localTool("tool"), executor) }
        val commits = mutableListOf<List<Message>>()
        val emitted = mutableListOf<TurnEvent>()
        val mapper = FakeProtocolMapper(
            listOf(
                listOf(
                    ProtocolEvent.TextDelta("let me"),
                    ProtocolEvent.ToolCallStarted("call1", "tool"),
                    ProtocolEvent.ToolCallDelta("call1", "tool", "{}"),
                    ProtocolEvent.ToolCallReady("call1", "tool", "{}"),
                    ProtocolEvent.Completed(stopReason = StopReason.ToolUse)
                ),
                listOf(ProtocolEvent.Completed(stopReason = StopReason.Stop))
            )
        )

        runLoop(
            loopRequest(emptyList(), toolRegistry = registry) { commits += it }.copy(protocolMapper = mapper),
            emitted
        )

        // 整条 commit：content = [Text, ToolCall]（§8.11 #1，不拆分）
        val assistant = (commits[0].single() as Message.Assistant).message
        assertEquals(
            listOf(ContentBlock.Text("let me"), ContentBlock.ToolCall("call1", "tool", "{}")),
            assistant.content
        )
        assertEquals(1, executor.calls.size)
    }

    @Test
    fun toolCallNotInPartialUntilReady() = runTest {
        val executor = RecordingToolExecutor()
        val registry = DefaultToolRegistry().apply { register(localTool("tool"), executor) }
        val emitted = mutableListOf<TurnEvent>()
        val mapper = FakeProtocolMapper(
            listOf(
                listOf(
                    ProtocolEvent.ToolCallStarted("call1", "tool"),
                    ProtocolEvent.ToolCallDelta("call1", "tool", "{}"),
                    ProtocolEvent.ToolCallReady("call1", "tool", "{}"),
                    ProtocolEvent.Completed(stopReason = StopReason.ToolUse)
                ),
                listOf(ProtocolEvent.Completed(stopReason = StopReason.Stop))
            )
        )

        runLoop(loopRequest(emptyList(), toolRegistry = registry).copy(protocolMapper = mapper), emitted)

        // Started / Delta 的 partial 不含 ToolCall 块（Ready 前不占位，§5.4）
        val started = emitted.filterIsInstance<TurnEvent.ToolCallStarted>().single()
        assertTrue(started.partial.content.none { it is ContentBlock.ToolCall })
        val delta = emitted.filterIsInstance<TurnEvent.ToolCallDelta>().single()
        assertTrue(delta.partial.content.none { it is ContentBlock.ToolCall })
        // Ready 的 partial 含该块
        val ready = emitted.filterIsInstance<TurnEvent.ToolCallReady>().single()
        assertTrue(ready.partial.content.any { it is ContentBlock.ToolCall })
    }

    // ── input 改写作用域（T5 语义在多轮下的回归） ───────────────────────

    @Test
    fun inputRewriteAppliesOnlyFirstRequest() = runTest {
        val executor = RecordingToolExecutor()
        val registry = DefaultToolRegistry().apply { register(localTool("tool"), executor) }
        val mapper = FakeProtocolMapper(
            listOf(
                listOf(
                    ProtocolEvent.ToolCallReady("call1", "tool", "{}"),
                    ProtocolEvent.Completed(stopReason = StopReason.ToolUse)
                ),
                listOf(
                    ProtocolEvent.TextDelta("answer"),
                    ProtocolEvent.Completed(stopReason = StopReason.Stop)
                )
            )
        )
        val hooks = listOf(object : Hooks {
            override suspend fun beforeInput(input: InputHolder) { input.write("rewritten", "h1") }
        })

        runLoop(loopRequest(emptyList(), toolRegistry = registry, hooks = hooks).copy(protocolMapper = mapper))

        // 第一轮：末尾 User 文本 = 改写值
        val firstUser = mapper.builtHistories[0].last() as Message.User
        assertEquals("rewritten", ((firstUser.content.single() as ContentBlock.Text).text))
        // 第二轮：末尾是 ToolResult（改写只作用于第一轮，不反复改写后续历史）
        assertFalse(mapper.builtHistories[1].last() is Message.User)
    }
}
