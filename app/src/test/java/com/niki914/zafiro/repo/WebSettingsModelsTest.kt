package com.niki914.zafiro.repo

import com.niki914.zafiro.mod.WebSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSettingsModelsTest {

    @Test
    fun isBeta_defaultsToFalseWhenFieldIsMissing() {
        val settings = WebSettings(jsonObject("""{"config":{"actions":{}}}"""))

        assertFalse(settings.isBeta)
    }

    @Test
    fun isBeta_readsExplicitBoolean() {
        val settings = WebSettings(jsonObject("""{"is_beta":true,"config":{"actions":{}}}"""))

        assertTrue(settings.isBeta)
    }

    @Test
    fun versionMetadata_defaultsRequestedAndResolvedToConfigVersion() {
        val settings = WebSettings(
            jsonObject(
                """
                {
                  "package_name":"com.heytap.speechassist",
                  "version_code":120803,
                  "config":{"actions":{}}
                }
                """.trimIndent()
            )
        )

        assertEquals("com.heytap.speechassist", settings.packageName)
        assertEquals(120803L, settings.versionCode)
        assertEquals(120803L, settings.requestedVersionCode)
        assertEquals(120803L, settings.resolvedVersionCode)
    }

    @Test
    fun nearestVersionCode_returnsClosestVersionByAbsoluteDistance() {
        val nearest = WebSettingsVersionFallback.nearestVersionCode(
            requestedVersionCode = 105,
            supportedVersionCodes = listOf(90, 100, 110, 130),
        )

        assertEquals(100L, nearest)
    }

    @Test
    fun nearestVersionCode_prefersLowerVersionWhenDistanceTies() {
        val nearest = WebSettingsVersionFallback.nearestVersionCode(
            requestedVersionCode = 105,
            supportedVersionCodes = listOf(110, 100),
        )

        assertEquals(100L, nearest)
    }

    @Test
    fun nearestVersionCode_returnsNullForEmptyList() {
        val nearest = WebSettingsVersionFallback.nearestVersionCode(
            requestedVersionCode = 105,
            supportedVersionCodes = emptyList(),
        )

        assertNull(nearest)
    }

    private fun jsonObject(json: String) = Json.parseToJsonElement(json).jsonObject
}
