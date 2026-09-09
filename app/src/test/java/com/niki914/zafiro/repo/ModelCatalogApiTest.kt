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
}
