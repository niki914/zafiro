package com.niki914.nexus.agentic.mod.feat.hyper

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class XiaoaiConfigJsonStructureTest {
    private val configFile: Path = Paths.get(
        "..",
        "server",
        "com.miui.voiceassist",
        "507013003",
        "config.json"
    )

    private fun actions(): JsonObject {
        assertTrue("config file must exist: $configFile", Files.exists(configFile))
        val root = Json.parseToJsonElement(configFile.toFile().readText()).jsonObject
        return root["config"]!!.jsonObject["actions"]!!.jsonObject
    }

    @Test
    fun removedExperimentalActions_areNotPresent() {
        val actions = actions()

        assertFalse(actions.containsKey("rewrite_native_instruction_batch"))
        assertFalse(actions.containsKey("block_native_procedure_operation"))
    }

    @Test
    fun existingWhitelistAction_keepsAllowedListStable() {
        val allowedFullNames = actions()
            .getValue("block_native_instruction_whitelist")
            .jsonObject
            .getValue("business")
            .jsonObject
            .getValue("allowed_instruction_full_names")
            .jsonArray
            .map { it.jsonPrimitive.content }

        assertTrue(allowedFullNames.isNotEmpty())
        assertTrue(allowedFullNames.contains("Nlp.UpdateStreamProperties"))
    }

    @Test
    fun unknownFields_doNotBreakRawJsonParsing() {
        val root = Json.parseToJsonElement(configFile.toFile().readText()).jsonObject
        val rootWithUnknownField = JsonObject(
            root + ("unknown_future_field" to buildJsonObject { put("enabled", true) })
        )

        assertTrue(rootWithUnknownField["config"]!!.jsonObject.containsKey("actions"))
    }

    @Test
    fun descriptorParameterStrings_arePreservedInRenderConfig() {
        val renderConfig = actions()
            .getValue("render_text_stream_card")
            .jsonObject
        val business = renderConfig
            .getValue("business")
            .jsonObject
        val target = renderConfig
            .getValue("target")
            .jsonObject

        assertEquals(
            emptyList<JsonElement>(),
            business.getValue("instruction_constructor_param_types").jsonArray
        )
        assertTrue(
            business
                .getValue("instruction_header_constructor_param_types")
                .jsonArray
                .map { it.jsonPrimitive.content }
                .contains("java.lang.String")
        )
        assertTrue(
            target
                .getValue("param_types")
                .jsonArray
                .map { it.jsonPrimitive.content }
                .contains("com.xiaomi.ai.api.common.Instruction")
        )
    }
}
