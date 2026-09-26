package com.niki914.zafiro.runtime.service

import com.niki914.zafiro.api.model.ConversationTurn
import com.niki914.zafiro.api.model.ToolInvocation
import com.niki914.zafiro.api.model.ToolOutcome
import com.niki914.zafiro.api.model.TurnBlock
import com.niki914.zafiro.api.model.TurnFailureCode
import com.niki914.zafiro.api.model.TurnId
import com.niki914.zafiro.chat.ToolStatusLabels
import org.junit.Assert.assertEquals
import org.junit.Test

class HostConversationProjectorTest {

    private val labels = ToolStatusLabels(
        called = "called",
        running = "running",
        success = "success",
        failed = "failed",
    )

    @Test
    fun render_pureText() {
        val turn = ConversationTurn(
            id = TurnId("t0"),
            userText = "Hi",
            blocks = listOf(
                TurnBlock.Text("b0", "Hello world!"),
            ),
        )

        assertEquals("Hello world!", HostConversationProjector.render(turn, labels))
    }

    @Test
    fun render_thinkingWithQuotesAndMultipleLines() {
        val turn = ConversationTurn(
            id = TurnId("t0"),
            userText = "Explain quantum physics",
            blocks = listOf(
                TurnBlock.Thinking(
                    id = "b0",
                    text = "First analyze the user query.\nStep 1: define quantum.\n\nStep 2: simplify.",
                    isComplete = false,
                ),
            ),
        )

        val expected = """
            > First analyze the user query.
            > Step 1: define quantum.
            >
            > Step 2: simplify.
        """.trimIndent()

        assertEquals(expected, HostConversationProjector.render(turn, labels))
    }

    @Test
    fun render_interleavedThinkingToolsAndText() {
        val turn = ConversationTurn(
            id = TurnId("t0"),
            userText = "Check weather",
            blocks = listOf(
                TurnBlock.Thinking(
                    id = "b0",
                    text = "Need weather tool for Beijing.",
                    isComplete = true,
                ),
                TurnBlock.Tool(
                    id = "b1",
                    invocation = ToolInvocation(id = "c1", name = "weather", label = "weather", argumentsJson = "{}"),
                    outcome = ToolOutcome.Succeeded("Sunny 25C"),
                ),
                TurnBlock.Tool(
                    id = "b2",
                    invocation = ToolInvocation(id = "c2", name = "air_quality", label = "air_quality", argumentsJson = "{}"),
                    outcome = null, // running
                ),
                TurnBlock.Text(
                    id = "b3",
                    text = "The weather today is sunny.",
                ),
            ),
        )

        val expected = """
            > Need weather tool for Beijing.

            `[weather] success`
            `[air_quality] running`
            The weather today is sunny.
        """.trimIndent()

        assertEquals(expected, HostConversationProjector.render(turn, labels))
    }

    @Test
    fun render_toolFailedAndFailureBlock() {
        val turn = ConversationTurn(
            id = TurnId("t0"),
            userText = "Do something",
            blocks = listOf(
                TurnBlock.Tool(
                    id = "b0",
                    invocation = ToolInvocation(id = "c1", name = "search", label = "search", argumentsJson = "{}"),
                    outcome = ToolOutcome.Failed("Network error"),
                ),
                TurnBlock.Failure(
                    id = "b1",
                    message = null,
                    code = TurnFailureCode.ConfigRequired,
                ),
            ),
        )

        val result = HostConversationProjector.render(turn, labels) { code ->
            "Localized error for $code"
        }

        val expected = """
            `[search] failed`
            Localized error for ConfigRequired
        """.trimIndent()

        assertEquals(expected, result)
    }

    @Test
    fun render_retryingBlock() {
        val turn = ConversationTurn(
            id = TurnId("t0"),
            userText = "Retry test",
            blocks = listOf(
                TurnBlock.Retrying(
                    id = "b0",
                    attempt = 2,
                    maxAttempts = 3,
                    delayMs = 1000L,
                    reason = "rate limited",
                ),
            ),
        )

        assertEquals("`[retrying] 2/3`", HostConversationProjector.render(turn, labels))
    }
}
