package com.niki914.okia.loop

import com.niki914.okia.error.RetryPolicy
import com.niki914.okia.fake.FakeHttpEngine
import com.niki914.okia.fake.FakeProtocolMapper
import com.niki914.okia.fake.RecordingToolExecutor
import com.niki914.okia.fake.localTool
import com.niki914.okia.hooks.Hooks
import com.niki914.okia.hooks.ToolCallHolder
import com.niki914.okia.hooks.ToolResultHolder
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.okia.message.StopReason
import com.niki914.okia.protocol.ProtocolEvent
import com.niki914.okia.protocol.RequestSnapshot
import com.niki914.okia.tooling.DefaultToolRegistry
import com.niki914.okia.transport.HttpTimeouts
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G5 快照整改测试：工具描述快照每段 buildRequest 前从 registry 现取
 * （§8.18）——即使 LoopRequest.snapshot.tools 是旧值（send 时固定），
 * 两轮工具循环的第二轮请求也反映段间注册的新工具（经 afterToolCall hook
 * 注册，模拟 MCP 刷新/回合间注册对后续请求可见的语义）。
 * 测试断言公开面可观察行为（每段 buildRequest 收到的 snapshot.tools），
 * 不依赖实现内部结构。
 */
class RealAgentLoopSnapshotTest {

    @Test
    fun toolsAreReSnapshottedPerSegment() = runTest {
        val registry = DefaultToolRegistry()
        registry.register(localTool("tool-a"), RecordingToolExecutor())
        // 段间注册新工具：第一轮工具执行完成后（afterToolCall）注册 tool-b，
        // 第二轮 buildRequest 前完成——快照现取应看到它。
        val toolB = mutableListOf<String>()
        val hooks = listOf(object : Hooks {
            override suspend fun afterToolCall(call: ToolCallHolder, result: ToolResultHolder) {
                registry.register(localTool("tool-b"), RecordingToolExecutor())
                toolB += call.name
            }
        })

        // 第一轮：模型产出工具调用 A（无文本）；第二轮：Stop。
        val rounds = listOf(
            listOf(
                ProtocolEvent.ToolCallReady("call-a", "tool-a", "{}"),
                ProtocolEvent.Completed(stopReason = StopReason.ToolUse)
            ),
            listOf(
                ProtocolEvent.TextDelta("done"),
                ProtocolEvent.Completed(stopReason = StopReason.Stop)
            )
        )
        val mapper = FakeProtocolMapper(rounds)

        val request = LoopRequest(
            snapshot = RequestSnapshot(
                endpoint = "https://api.test/v1",
                apiKey = "k",
                model = "m",
                systemPrompt = null,
                temperature = 0.7f,
                maxTokens = 100,
                headers = emptyMap(),
                timeouts = HttpTimeouts(1_000, 1_000, 1_000),
                // send 时旧快照：tools 为空（G5 整改后每段覆盖，此处应被现取覆盖）
                tools = emptyList()
            ),
            history = listOf(Message.User(listOf(ContentBlock.Text("hi")))),
            input = "hi",
            options = LoopOptions(),
            idleTimeoutSeconds = null,
            toolRegistry = registry,
            protocolMapper = mapper,
            hooks = hooks,
            httpEngine = FakeHttpEngine(),
            retryPolicy = RetryPolicy(),
            onCommit = {}
        )

        val result = RealAgentLoop().run(request) { }
        assertTrue(result is TurnResult.Completed)
        assertTrue(toolB == listOf("tool-a")) // 工具 A 执行过，hook 注册了 tool-b

        // 两次 buildRequest（两轮段）：snapshot.tools 均为现取
        org.junit.Assert.assertEquals(2, mapper.builtSnapshots.size)
        // 第一段：snapshot.tools 初始为空，仍被现取覆盖为 [tool-a]
        val firstTools = mapper.builtSnapshots[0].tools.map { it.name }
        assertTrue("tool-a" in firstTools)
        assertTrue("tool-b" !in firstTools)
        // 第二段：段间注册的 tool-b 可见
        val secondTools = mapper.builtSnapshots[1].tools.map { it.name }
        assertTrue("tool-a" in secondTools)
        assertTrue("tool-b" in secondTools)
    }
}