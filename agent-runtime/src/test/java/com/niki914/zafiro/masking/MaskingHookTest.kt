// 保护：beforeSerialization 命中脱敏规则时改写消息历史，ToolResult 与 User/Assistant 敏感内容均被脱敏
package com.niki914.zafiro.masking

import com.niki914.okia.hooks.SerializationHolder
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.okia.message.ToolCallOutcome
import com.niki914.okia.protocol.RequestSnapshot
import com.niki914.okia.transport.HttpTimeouts
import com.niki914.zafiro.settings.model.RuntimeMaskingRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MaskingHookTest {

    private val defaultRule = RuntimeMaskingRule(
        id = "default",
        name = "default",
        enabled = true,
        kind = "API_KEY",
        patterns = listOf("""\bsk-[a-zA-Z0-9_\-]{20,}\b"""),
    )

    private fun stubSnapshot() = RequestSnapshot(
        endpoint = "https://example.com/v1",
        apiKey = "test-key",
        model = "test-model",
        systemPrompt = "system prompt",
        temperature = 0.5f,
        maxTokens = 2048,
        headers = emptyMap(),
        timeouts = HttpTimeouts(connectMs = 1000L, readMs = 1000L, writeMs = 1000L),
        tools = emptyList(),
    )

    @Test
    fun beforeSerialization_masksUserSensitiveContent() = runTest {
        val hook = MaskingHook(
            listMaskingRules = { listOf(defaultRule) },
        )

        val history = listOf(
            Message.User(listOf(ContentBlock.Text("My key is sk-12345678901234567890"))),
        )
        val holder = SerializationHolder(stubSnapshot(), history)

        hook.beforeSerialization(holder)

        assertEquals("masking", holder.lastWriter)
        val modifiedUser = holder.history.first() as Message.User
        val textBlock = modifiedUser.content.first() as ContentBlock.Text

        assertEquals("My key is {{API_KEY_1}}", textBlock.text)
    }

    @Test
    fun beforeSerialization_doesNothingWhenNoRulesMatch() = runTest {
        val hook = MaskingHook(
            listMaskingRules = { listOf(defaultRule) },
        )

        val history = listOf(
            Message.User(listOf(ContentBlock.Text("Ordinary question without secret"))),
        )
        val holder = SerializationHolder(stubSnapshot(), history)

        hook.beforeSerialization(holder)

        assertNull(holder.lastWriter)
        assertEquals(history, holder.history)
    }

    @Test
    fun beforeSerialization_masksToolResultSuccessAndFailure() = runTest {
        val hook = MaskingHook(
            listMaskingRules = { listOf(defaultRule) },
        )

        val history = listOf(
            Message.ToolResult(
                callId = "call_1",
                toolName = "fetch",
                outcome = ToolCallOutcome.Success(content = "data with key sk-12345678901234567890"),
            ),
            Message.ToolResult(
                callId = "call_2",
                toolName = "curl",
                outcome = ToolCallOutcome.Failure(
                    message = "error with key sk-abcdefabcdefabcdefabcd",
                    content = "traceback with sk-abcdefabcdefabcdefabcd",
                ),
            ),
        )
        val holder = SerializationHolder(stubSnapshot(), history)

        hook.beforeSerialization(holder)

        assertEquals("masking", holder.lastWriter)
        val r1 = holder.history[0] as Message.ToolResult
        val r2 = holder.history[1] as Message.ToolResult

        val successOutcome = r1.outcome as ToolCallOutcome.Success
        val failureOutcome = r2.outcome as ToolCallOutcome.Failure

        assertEquals("data with key {{API_KEY_1}}", successOutcome.content)
        assertEquals("error with key {{API_KEY_2}}", failureOutcome.message)
        assertEquals("traceback with {{API_KEY_2}}", failureOutcome.content)
    }
}
