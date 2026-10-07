package com.niki914.zafiro.runtime.client

import com.niki914.zafiro.runtime.ipc.RenderFrame
import com.niki914.zafiro.runtime.ipc.ToolItem
import com.niki914.zafiro.runtime.ipc.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantFrameTest {

    @Test
    fun toAssistantFrame_mapsFieldsCorrectly() {
        val renderFrame = RenderFrame(
            content = "Final response",
            thinking = "Step 1, step 2",
            isThinkingComplete = true,
            tools = listOf(ToolItem(name = "search", status = ToolStatus.SUCCESS)),
            isFirst = true,
            isFinal = true,
        )

        val assistantFrame = renderFrame.toAssistantFrame()
        assertTrue(assistantFrame is AssistantFrame.Update)

        val update = assistantFrame as AssistantFrame.Update
        assertEquals("Final response", update.content)
        assertEquals("Step 1, step 2", update.thinking?.text)
        assertEquals(true, update.thinking?.isComplete)
        assertEquals(listOf(ToolItem("search", ToolStatus.SUCCESS)), update.tools)
        assertEquals(true, update.isFirst)
        assertEquals(true, update.isFinal)
    }

    @Test
    fun toAssistantFrame_handlesNullOrBlankThinking() {
        val renderFrame = RenderFrame(
            content = "Plain text",
            thinking = "   ",
            isThinkingComplete = false,
            tools = emptyList(),
        )

        val update = renderFrame.toAssistantFrame() as AssistantFrame.Update
        assertEquals("Plain text", update.content)
        assertNull(update.thinking)
    }
}
