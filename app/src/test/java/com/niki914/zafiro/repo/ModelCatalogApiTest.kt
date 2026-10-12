package com.niki914.zafiro.repo

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelCatalogApiTest {

    @Test
    fun `parseModelIds extracts sorted distinct ids`() {
        val body = """{"data":[{"id":"gpt-b"},{"id":"gpt-a"},{"id":"gpt-b"},{"id":"  "}]}"""
        assertEquals(listOf("gpt-a", "gpt-b"), ModelCatalogApi.parseModelIds(body))
    }

    @Test
    fun `parseModelIds returns empty on missing data or garbage`() {
        assertEquals(emptyList<String>(), ModelCatalogApi.parseModelIds("""{"object":"list"}"""))
        assertEquals(emptyList<String>(), ModelCatalogApi.parseModelIds("not json"))
        assertEquals(emptyList<String>(), ModelCatalogApi.parseModelIds(""))
    }

    @Test
    fun `parseModelIds handles anthropic shape`() {
        val body = """{"data":[{"id":"claude-x","display_name":"X"},{"id":""}]}"""
        assertEquals(listOf("claude-x"), ModelCatalogApi.parseModelIds(body))
    }

    @Test
    fun `parseCatalogFacts probes deepseek window and max output`() {
        val body = """
            {"data":[{"id":"deepseek-chat","context_window":1048576,"max_output_tokens":393216}]}
        """.trimIndent()
        val facts = ModelCatalogApi.parseCatalogFacts(body)
        assertEquals(
            CloudModelFacts(windowTokens = 1_048_576L, maxOutputTokens = 393_216L),
            facts["deepseek-chat"],
        )
    }

    @Test
    fun `parseCatalogFacts probes openrouter top_provider`() {
        val body = """
            {"data":[{"id":"openai/gpt-4o","context_length":128000,
              "top_provider":{"context_length":128000,"max_completion_tokens":16384}}]}
        """.trimIndent()
        val facts = ModelCatalogApi.parseCatalogFacts(body)
        assertEquals(
            CloudModelFacts(windowTokens = 128_000L, maxOutputTokens = 16_384L),
            facts["openai/gpt-4o"],
        )
    }

    @Test
    fun `parseCatalogFacts drops entries without any usable field`() {
        // Google 只回 id：不应当入表（留空给下一层解析源），也不应给老缓存投毒
        val body = """
            {"data":[{"id":"models/gemini-2.5-flash","object":"model"},
                     {"id":"a","context_length":1000},{"object":"model"}]}
        """.trimIndent()
        val facts = ModelCatalogApi.parseCatalogFacts(body)
        assertEquals(mapOf("a" to CloudModelFacts(windowTokens = 1_000L)), facts)
    }

    @Test
    fun `parseCatalogFacts returns empty on garbage and rejects non positive values`() {
        assertEquals(
            emptyMap<String, CloudModelFacts>(),
            ModelCatalogApi.parseCatalogFacts("not json")
        )
        assertEquals(
            emptyMap<String, CloudModelFacts>(),
            ModelCatalogApi.parseCatalogFacts(
                """{"data":[{"id":"x","context_length":0,"max_tokens":-5}]}"""
            ),
        )
    }
}
