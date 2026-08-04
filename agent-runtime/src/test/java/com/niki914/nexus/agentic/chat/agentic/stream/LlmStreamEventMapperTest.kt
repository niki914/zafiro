package com.niki914.nexus.agentic.chat.agentic.stream

import com.niki914.nexus.agentic.chat.LlmStreamEvent
import com.niki914.kai.SessionEvent
import com.niki914.kai.ToolCallKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmStreamEventMapperTest {

    @Test
    fun `ToolSucceeded with failure envelope and whitelisted toolName returns ToolFailed`() {
        val failureResult =
            "#!tool-result\n#!status: failure\n#!code: VERSION_MISMATCH\n#!message: Token expired\n\nsome payload"

        val event = SessionEvent.ToolSucceeded(
            callId = "call1",
            toolName = "screen_operation_accessibility",
            kind = ToolCallKind.Local,
            resultJson = failureResult,
        )

        val result = LlmStreamEventMapper.map(event, StringBuilder(), 0L, "default error")
        assertTrue(result is LlmStreamEvent.ToolFailed)
        assertEquals("Token expired", (result as LlmStreamEvent.ToolFailed).message)
    }

    @Test
    fun `ToolSucceeded with success envelope and whitelisted toolName returns ToolSucceeded`() {
        val successResult = "#!tool-result\n#!status: success\n\ntree yaml content"

        val event = SessionEvent.ToolSucceeded(
            callId = "call2",
            toolName = "screen_operation_accessibility",
            kind = ToolCallKind.Local,
            resultJson = successResult,
        )

        val result = LlmStreamEventMapper.map(event, StringBuilder(), 0L, "default error")
        assertTrue(result is LlmStreamEvent.ToolSucceeded)
        assertEquals("tree yaml content", (result as LlmStreamEvent.ToolSucceeded).outputText)
    }
}
