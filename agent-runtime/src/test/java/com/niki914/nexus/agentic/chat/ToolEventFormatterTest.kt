package com.niki914.nexus.agentic.chat

import com.niki914.nexus.agentic.chat.agentic.stream.ToolEventFormatter
import com.niki914.nexus.agentic.chat.agentic.stream.ToolRenderMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolEventFormatterTest {

    @Test
    fun format_onlyFormatsToolEventsAndHonorsRenderMode() {
        val running = LlmStreamEvent.ToolRunning(
            ToolCallStatus(
                callId = "call-1",
                name = "search",
                label = "Search",
                kind = ToolCallKind.Local
            )
        )

        val append = ToolEventFormatter.format(running, ToolRenderMode.AppendOnly)
        val replace = ToolEventFormatter.format(running, ToolRenderMode.ReplaceStatus)

        assertEquals("Calling tool: Search", append?.text)
        assertFalse(append?.replacePreviousStatus ?: true)
        assertEquals("Calling tool: Search", replace?.text)
        assertTrue(replace?.replacePreviousStatus ?: false)
        assertNull(
            ToolEventFormatter.format(
                LlmStreamEvent.Completed,
                ToolRenderMode.AppendOnly
            )
        )
    }
}
