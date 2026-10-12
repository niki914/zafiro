package com.niki914.zafiro.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenRouterCatalogApiTest {

    private val catalog = mapOf(
        "googlegemini25pro" to CloudModelFacts(windowTokens = 1_048_576L),
        "moonshotaikimik2" to CloudModelFacts(windowTokens = 262_144L),
        "deepseekdeepseekchat" to CloudModelFacts(
            windowTokens = 128_000L,
            maxOutputTokens = 8_192L,
        ),
    )

    @Test
    fun normalize_foldsCaseAndSeparators() {
        assertEquals("claudesonnet45", OpenRouterCatalogApi.normalize("Claude-Sonnet-4.5"))
        assertEquals("googlegemini25pro", OpenRouterCatalogApi.normalize("google/gemini-2.5-pro"))
        assertEquals("modelsgemini25pro", OpenRouterCatalogApi.normalize("models/gemini-2.5-pro"))
        assertEquals("", OpenRouterCatalogApi.normalize("  --  "))
    }

    @Test
    fun catalogKey_stripsVariantSuffix() {
        assertEquals("openaigpt4o", OpenRouterCatalogApi.catalogKey("openai/gpt-4o:extended"))
        assertEquals(
            "deepseekdeepseekchat",
            OpenRouterCatalogApi.catalogKey("deepseek/deepseek-chat:free")
        )
        assertEquals(
            "deepseekdeepseekchat",
            OpenRouterCatalogApi.catalogKey("deepseek/deepseek-chat")
        )
    }

    @Test
    fun match_exactNormalizedHit() {
        assertEquals(
            128_000L,
            OpenRouterCatalogApi.match(catalog, "deepseek/deepseek-chat")?.windowTokens,
        )
    }

    @Test
    fun match_bareNameHitAcrossOrgPrefix() {
        // Google 下拉存 models/gemini-…，OpenRouter 是 google/gemini-…；
        // 用户侧无组织前缀（kimi-k2 ↔ moonshotai/kimi-k2）同理
        assertEquals(
            1_048_576L,
            OpenRouterCatalogApi.match(catalog, "models/gemini-2.5-pro")?.windowTokens,
        )
        assertEquals(
            262_144L,
            OpenRouterCatalogApi.match(catalog, "kimi-k2")?.windowTokens,
        )
    }

    @Test
    fun match_stripsVariantSuffixFromQuery() {
        assertEquals(
            128_000L,
            OpenRouterCatalogApi.match(catalog, "deepseek/deepseek-chat:free")?.windowTokens,
        )
    }

    @Test
    fun match_returnsNullOnMissAndGarbage() {
        assertNull(OpenRouterCatalogApi.match(catalog, "totally-unknown-model"))
        assertNull(OpenRouterCatalogApi.match(catalog, ""))
        assertNull(OpenRouterCatalogApi.match(emptyMap(), "deepseek-chat"))
    }
}
