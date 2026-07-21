package com.niki914.nexus.agentic.chat

import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.nexus.agentic.chat.agentic.buildin.impl.CreateCustomToolBuiltin
import com.niki914.nexus.agentic.chat.agentic.custom.CustomToolCreateRequest
import com.niki914.nexus.agentic.runtime.settings.RuntimeEnvironment
import com.niki914.s3ss10n.LocalToolConfig
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeCustomTool as CustomTool
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeCustomToolValidation as CustomToolValidation

class CreateCustomToolBuiltinTest {
    @After
    fun tearDown() {
        RuntimeEnvironment.clearForTest()
    }

    @Test
    fun configure_registersDescriptionAndSchemaWithDisabledDefault() {
        val config = LocalToolConfig()

        CreateCustomToolBuiltin().configure(config)

        assertTrue(config.description.contains("Create or update a custom tool"))
        val schema = Json.parseToJsonElement(config.rawInputSchemaJson!!).jsonObject
        val properties = schema["properties"]!!.jsonObject
        assertEquals(
            listOf("name", "description", "command"),
            schema["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("string", properties["name"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(
            "string",
            properties["description"]!!.jsonObject["type"]!!.jsonPrimitive.content
        )
        assertEquals("string", properties["command"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val commandDescription =
            properties["command"]!!.jsonObject["description"]!!.jsonPrimitive.content
        assertTrue(commandDescription.contains("Normal Android shell"))
        assertTrue(commandDescription.contains("su -c"))
        assertTrue(commandDescription.contains("cd /path && cmd"))
        assertEquals("boolean", properties["enabled"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse(properties["enabled"]!!.jsonObject["default"]!!.jsonPrimitive.boolean)
        assertEquals(
            "boolean",
            properties["overwrite"]!!.jsonObject["type"]!!.jsonPrimitive.content
        )
        assertFalse(properties["overwrite"]!!.jsonObject["default"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun parseArguments_readsRequiredFieldsAndBooleanDefaults() {
        val method = CreateCustomToolBuiltin::class.java.getDeclaredMethod(
            "parseArguments",
            String::class.java,
        )
        method.isAccessible = true

        val request = method.invoke(
            CreateCustomToolBuiltin(),
            """
            {
              "name": "battery_status",
              "description": "Read current battery status.",
              "command": "dumpsys battery"
            }
            """.trimIndent(),
        ) as CustomToolCreateRequest

        assertEquals("battery_status", request.name)
        assertEquals("Read current battery status.", request.description)
        assertEquals("dumpsys battery", request.command)
        assertFalse(request.enabled)
        assertFalse(request.overwrite)
    }

    @Test
    fun invoke_returnsInvalidArgumentsJsonForNonObjectArguments() = runTest {
        val result = CreateCustomToolBuiltin().invoke(
            BuiltinToolRequest(
                name = "create_custom_tool",
                argumentsJson = "[]",
            )
        )

        assertFalse(result.ok)
        assertEquals("INVALID_ARGUMENTS_JSON", result.code)
        assertTrue(result.fieldErrors.containsKey("argumentsJson"))
    }

    @Test
    fun invoke_writesCreatedToolThroughRuntimeSettingsGateway() = runTest {
        val store = installRuntimeSettingsGatewayForTest()

        val result = CreateCustomToolBuiltin().invoke(
            BuiltinToolRequest(
                name = "create_custom_tool",
                argumentsJson = """
                    {
                      "name": "battery_status",
                      "description": "Read current battery status.",
                      "command": "dumpsys battery",
                      "enabled": true,
                      "overwrite": false
                    }
                """.trimIndent(),
            )
        )

        assertTrue(result.ok)
        assertEquals(1, store.writeCount)
        assertEquals(
            listOf(
                CustomTool(
                    "battery_status",
                    "Read current battery status.",
                    "dumpsys battery",
                    enabled = true
                )
            ),
            store.customTools,
        )
    }

    @Test
    fun invoke_mapsExecutionRuleValidationToRuleBlocked() = runTest {
        val store = installRuntimeSettingsGatewayForTest()
        store.nextSaveCustomToolValidation = CustomToolValidation(
            field = "command",
            message = "Command blocked by execution rule '危险删改' with pattern '\\brm\\s+-rf\\b'.",
        )

        val result = CreateCustomToolBuiltin().invoke(
            BuiltinToolRequest(
                name = "create_custom_tool",
                argumentsJson = """
                    {
                      "name": "wipe_data",
                      "description": "Dangerous command.",
                      "command": "rm -rf /data/local/tmp/cache"
                    }
                """.trimIndent(),
            )
        )

        assertFalse(result.ok)
        assertEquals("RULE_BLOCKED", result.code)
        assertTrue(result.message.contains("危险删改"))
        assertEquals(result.message, result.hint)
        assertEquals(result.message, result.fieldErrors["command"])
    }

}
