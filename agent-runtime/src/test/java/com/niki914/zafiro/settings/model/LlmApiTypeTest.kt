package com.niki914.zafiro.settings.model

import org.junit.Assert.assertEquals
import org.junit.Test

class LlmApiTypeTest {
    @Test
    fun fromProvider_mapsKnownProvidersAndFallsBackToOpenAi() {
        assertEquals(LlmApiType.Anthropic, LlmApiType.fromProvider("anthropic"))
        assertEquals(LlmApiType.DeepSeek, LlmApiType.fromProvider(" deepseek "))
        assertEquals(LlmApiType.OpenAI, LlmApiType.fromProvider("openai"))
        assertEquals(LlmApiType.OpenAI, LlmApiType.fromProvider("google"))
        assertEquals(LlmApiType.OpenAI, LlmApiType.fromProvider(""))
    }
}
