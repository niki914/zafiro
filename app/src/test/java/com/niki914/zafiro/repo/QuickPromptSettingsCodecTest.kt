// 保护：快速提示词 JSON 编解码的容错与双向保真（畸形数据过滤、字段完整性丢失时拦截）
package com.niki914.zafiro.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickPromptSettingsCodecTest {

    @Test
    fun parsePrompts_filtersInvalidAndEmptyEntries() {
        val json = """
            {
                "prompts": [
                    { "name": "btw", "prompt": "By the way", "enabled": true },
                    { "name": "", "prompt": "Empty name", "enabled": true },
                    { "name": "no_prompt", "prompt": "   ", "enabled": true },
                    { "name": "def_enabled", "prompt": "Default enabled" }
                ]
            }
        """.trimIndent()

        val parsed = QuickPromptSettingsCodec.parsePrompts(json)
        assertEquals(2, parsed.size)
        assertEquals("btw", parsed[0].name)
        assertEquals("By the way", parsed[0].prompt)
        assertTrue(parsed[0].enabled)

        assertEquals("def_enabled", parsed[1].name)
        assertEquals("Default enabled", parsed[1].prompt)
        assertTrue(parsed[1].enabled)
    }

    @Test
    fun encodeAndParse_roundtripPreservesData() {
        val originals = listOf(
            RuntimeQuickPrompt(name = "btw", prompt = "By the way", enabled = true),
            RuntimeQuickPrompt(name = "translate", prompt = "Please translate to English:", enabled = false),
        )

        val encoded = QuickPromptSettingsCodec.encodePrompts(originals)
        val decoded = QuickPromptSettingsCodec.parsePrompts(encoded)

        assertEquals(originals, decoded)
    }
}
