package com.niki914.zafiro.runtime.client

import com.niki914.zafiro.runtime.ipc.RenderFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AssistantFrameTest {

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
