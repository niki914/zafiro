package com.niki914.zafiro.app.ui.model

import com.niki914.zafiro.settings.model.LlmProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NvidiaPresetTest {
    @Test
    fun `nvidia is a selectable provider using the OpenAI chat completions protocol`() {
        val spec = ProviderSpecs.all.firstOrNull { it.id == "nvidia" }
        assertNotNull("NVIDIA preset must be registered", spec)
        assertEquals("NVIDIA NIM", spec!!.brandName)
        assertEquals("openai-chat-completions", spec.defaultProtocol)
        assertEquals(LlmProtocol.OpenAiChatCompletions, LlmProtocol.fromWire(spec.defaultProtocol))
        assertEquals("https://integrate.api.nvidia.com/v1/chat/completions", spec.officialEndpoint)
    }

    @Test
    fun `model discovery url is derived from the chat endpoint`() {
        assertEquals(
            "https://integrate.api.nvidia.com/v1/models",
            EndpointInference.modelsUrl("https://integrate.api.nvidia.com/v1/chat/completions", LlmProtocol.OpenAiChatCompletions),
        )
    }

    @Test
    fun `the preset endpoint is recognised so switching protocols does not prompt`() {
        assertTrue(EndpointInference.isPresetEndpoint("https://integrate.api.nvidia.com/v1/chat/completions"))
    }

    @Test
    fun `lookup by id finds nvidia and unknown ids still fall back`() {
        assertEquals("nvidia", ProviderSpecs.find("nvidia").id)
        assertEquals(ProviderSpecs.default.id, ProviderSpecs.find("nope").id)
    }
}
