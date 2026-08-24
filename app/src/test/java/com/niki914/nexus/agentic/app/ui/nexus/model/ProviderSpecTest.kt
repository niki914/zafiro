package com.niki914.nexus.agentic.app.ui.nexus.model

import com.niki914.nexus.agentic.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderSpecTest {
    @Test
    fun google_usesMaterialFallbackForButtonColors() {
        val google = ProviderSpecs.find("google")

        assertNull(google.visualTokens.button.darkContainerColorRes)
        assertNull(google.visualTokens.button.lightContainerColorRes)
        assertNull(google.visualTokens.button.darkContentColorRes)
        assertNull(google.visualTokens.button.lightContentColorRes)
    }

    @Test
    fun deepseek_usesFineGrainedButtonResourceNames() {
        val deepseek = ProviderSpecs.find("deepseek")

        assertEquals(
            R.color.provider_deepseek_button_dark_container,
            deepseek.visualTokens.button.darkContainerColorRes
        )
        assertEquals(
            R.color.provider_deepseek_button_light_container,
            deepseek.visualTokens.button.lightContainerColorRes
        )
        assertEquals(
            R.color.provider_deepseek_button_dark_content,
            deepseek.visualTokens.button.darkContentColorRes
        )
        assertEquals(
            R.color.provider_deepseek_button_light_content,
            deepseek.visualTokens.button.lightContentColorRes
        )
    }

    @Test
    fun providersExposeExampleModelIds() {
        assertEquals("deepseek-v4-pro", ProviderSpecs.find("deepseek").exampleModelId)
        assertEquals("gpt-5.4", ProviderSpecs.find("openai").exampleModelId)
        assertEquals("claude-sonnet-4-6", ProviderSpecs.find("anthropic").exampleModelId)
        assertEquals("gemini-3.5-flash", ProviderSpecs.find("google").exampleModelId)
    }
}
