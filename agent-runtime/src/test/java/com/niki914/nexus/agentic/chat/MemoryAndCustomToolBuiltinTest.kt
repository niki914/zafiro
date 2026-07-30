package com.niki914.nexus.agentic.chat

import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.nexus.agentic.chat.agentic.buildin.impl.MemorizeBuiltin
import com.niki914.nexus.agentic.chat.agentic.buildin.impl.ReadCustomToolBuiltin
import com.niki914.nexus.agentic.runtime.settings.RuntimeEnvironment
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeCustomTool as CustomTool

class MemoryAndCustomToolBuiltinTest {
    @After
    fun tearDown() {
        RuntimeEnvironment.clearForTest()
    }

    @Test
    fun memorize_addWritesTrimmedMemoryAndReturnsSuccessJson() = runTest {
        val store = installRuntimeSettingsGatewayForTest()

        val resultJson = MemorizeBuiltin().invokeRawJson(
            BuiltinToolRequest(
                name = "memorize",
                argumentsJson = """{"action":"add","content":"  User prefers concise answers.  "}""",
            )
        )

        val json = Json.parseToJsonElement(resultJson).jsonObject
        assertTrue(json["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("add", json["action"]!!.jsonPrimitive.content)
        assertEquals(listOf("User prefers concise answers."), store.memories)
        assertEquals(1, store.writeCount)
    }

    @Test
    fun memorize_addReturnsStructuredErrorForBlankContent() = runTest {
        installRuntimeSettingsGatewayForTest()

        val resultJson = MemorizeBuiltin().invokeRawJson(
            BuiltinToolRequest(
                name = "memorize",
                argumentsJson = """{"action":"add","content":"   "}""",
            )
        )

        val json = Json.parseToJsonElement(resultJson).jsonObject
        assertFalse(json["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("INVALID_ARGUMENTS", json["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun memorize_missingActionReturnsError() = runTest {
        installRuntimeSettingsGatewayForTest()

        val resultJson = MemorizeBuiltin().invokeRawJson(
            BuiltinToolRequest(
                name = "memorize",
                argumentsJson = """{"content":"some fact"}""",
            )
        )

        val json = Json.parseToJsonElement(resultJson).jsonObject
        assertFalse(json["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("INVALID_ARGUMENTS", json["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun memorize_unknownActionReturnsError() = runTest {
        installRuntimeSettingsGatewayForTest()

        val resultJson = MemorizeBuiltin().invokeRawJson(
            BuiltinToolRequest(
                name = "memorize",
                argumentsJson = """{"action":"remvoe","content":"typo"}""",
            )
        )

        val json = Json.parseToJsonElement(resultJson).jsonObject
        assertFalse(json["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("INVALID_ARGUMENTS", json["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun memorize_explicitAddActionWorks() = runTest {
        val store = installRuntimeSettingsGatewayForTest()

        val resultJson = MemorizeBuiltin().invokeRawJson(
            BuiltinToolRequest(
                name = "memorize",
                argumentsJson = """{"action":"add","content":"explicit add"}""",
            )
        )

        val json = Json.parseToJsonElement(resultJson).jsonObject
        assertTrue(json["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("add", json["action"]!!.jsonPrimitive.content)
        assertEquals(listOf("explicit add"), store.memories)
    }

    @Test
    fun memorize_removeDeletesByOldText() = runTest {
        val store = installRuntimeSettingsGatewayForTest()
        store.memories.addAll(listOf("keep", "delete-me-please", "also-keep"))

        val resultJson = MemorizeBuiltin().invokeRawJson(
            BuiltinToolRequest(
                name = "memorize",
                argumentsJson = """{"action":"remove","old_text":"delete-me"}""",
            )
        )

        val json = Json.parseToJsonElement(resultJson).jsonObject
        assertTrue(json["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("remove", json["action"]!!.jsonPrimitive.content)
        assertEquals(listOf("keep", "also-keep"), store.memories)
    }

    @Test
    fun memorize_removeReturnsErrorForNotFound() = runTest {
        val store = installRuntimeSettingsGatewayForTest()
        store.memories.add("only")

        val resultJson = MemorizeBuiltin().invokeRawJson(
            BuiltinToolRequest(
                name = "memorize",
                argumentsJson = """{"action":"remove","old_text":"nonexistent"}""",
            )
        )

        val json = Json.parseToJsonElement(resultJson).jsonObject
        assertFalse(json["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("NOT_FOUND", json["code"]!!.jsonPrimitive.content)
        assertEquals(listOf("only"), store.memories)
    }

    @Test
    fun memorize_removeReturnsErrorForAmbiguousMatch() = runTest {
        val store = installRuntimeSettingsGatewayForTest()
        store.memories.addAll(listOf("User prefers dark mode", "User prefers concise answers"))

        val resultJson = MemorizeBuiltin().invokeRawJson(
            BuiltinToolRequest(
                name = "memorize",
                argumentsJson = """{"action":"remove","old_text":"User prefers"}""",
            )
        )

        val json = Json.parseToJsonElement(resultJson).jsonObject
        assertFalse(json["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("AMBIGUOUS_MATCH", json["code"]!!.jsonPrimitive.content)
        assertEquals(2, store.memories.size)
    }

    @Test
    fun memorize_replaceUpdatesEntry() = runTest {
        val store = installRuntimeSettingsGatewayForTest()
        store.memories.addAll(listOf("keep", "User prefers dark mode", "also-keep"))

        val resultJson = MemorizeBuiltin().invokeRawJson(
            BuiltinToolRequest(
                name = "memorize",
                argumentsJson = """{"action":"replace","old_text":"dark mode","content":"User prefers light mode"}""",
            )
        )

        val json = Json.parseToJsonElement(resultJson).jsonObject
        assertTrue(json["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("replace", json["action"]!!.jsonPrimitive.content)
        assertEquals(listOf("keep", "also-keep", "User prefers light mode"), store.memories)
    }

    @Test
    fun memorize_replaceReturnsErrorForNotFound() = runTest {
        val store = installRuntimeSettingsGatewayForTest()
        store.memories.add("only")

        val resultJson = MemorizeBuiltin().invokeRawJson(
            BuiltinToolRequest(
                name = "memorize",
                argumentsJson = """{"action":"replace","old_text":"nonexistent","content":"new"}""",
            )
        )

        val json = Json.parseToJsonElement(resultJson).jsonObject
        assertFalse(json["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("NOT_FOUND", json["code"]!!.jsonPrimitive.content)
        assertEquals(listOf("only"), store.memories)
    }

    @Test
    fun memorize_replaceReturnsErrorForAmbiguousMatch() = runTest {
        val store = installRuntimeSettingsGatewayForTest()
        store.memories.addAll(listOf("User prefers dark mode", "User prefers concise answers"))

        val resultJson = MemorizeBuiltin().invokeRawJson(
            BuiltinToolRequest(
                name = "memorize",
                argumentsJson = """{"action":"replace","old_text":"User prefers","content":"new pref"}""",
            )
        )

        val json = Json.parseToJsonElement(resultJson).jsonObject
        assertFalse(json["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("AMBIGUOUS_MATCH", json["code"]!!.jsonPrimitive.content)
        assertEquals(2, store.memories.size)
    }

    @Test
    fun memorize_replaceAllowsDuplicateMatchWhenIdenticalContent() = runTest {
        val store = installRuntimeSettingsGatewayForTest()
        store.memories.addAll(listOf("same entry", "same entry"))

        val resultJson = MemorizeBuiltin().invokeRawJson(
            BuiltinToolRequest(
                name = "memorize",
                argumentsJson = """{"action":"replace","old_text":"same entry","content":"updated entry"}""",
            )
        )

        val json = Json.parseToJsonElement(resultJson).jsonObject
        assertTrue(json["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("replace", json["action"]!!.jsonPrimitive.content)
        assertEquals(listOf("same entry", "updated entry"), store.memories)
    }

    @Test
    fun readCustomTool_returnsAllCustomToolImplementationsWhenNameIsOmitted() = runTest {
        installRuntimeSettingsGatewayForTest(
            FakeRuntimeSettingsGateway(
                customTools = listOf(
                    CustomTool(
                        "battery_status",
                        "Read battery.",
                        "dumpsys battery",
                        enabled = true
                    ),
                    CustomTool("wifi_status", "Read wifi.", "cmd wifi status", enabled = false),
                )
            )
        )

        val resultJson = ReadCustomToolBuiltin().invokeRawJson(
            BuiltinToolRequest(
                name = "read_custom_tool",
                argumentsJson = "{}",
            )
        )

        val json = Json.parseToJsonElement(resultJson).jsonObject
        assertTrue(json["ok"]!!.jsonPrimitive.content.toBoolean())
        val tools = json["tools"]!!.jsonArray
        assertEquals(2, tools.size)
        assertEquals("battery_status", tools[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("dumpsys battery", tools[0].jsonObject["command"]!!.jsonPrimitive.content)
        assertEquals("wifi_status", tools[1].jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun readCustomTool_returnsSingleCustomToolByName() = runTest {
        installRuntimeSettingsGatewayForTest(
            FakeRuntimeSettingsGateway(
                customTools = listOf(
                    CustomTool(
                        "battery_status",
                        "Read battery.",
                        "dumpsys battery",
                        enabled = true
                    ),
                )
            )
        )

        val resultJson = ReadCustomToolBuiltin().invokeRawJson(
            BuiltinToolRequest(
                name = "read_custom_tool",
                argumentsJson = """{"name":"battery_status"}""",
            )
        )

        val tool = Json.parseToJsonElement(resultJson).jsonObject["tool"]!!.jsonObject
        assertEquals("battery_status", tool["name"]!!.jsonPrimitive.content)
        assertEquals("Read battery.", tool["description"]!!.jsonPrimitive.content)
        assertEquals("dumpsys battery", tool["command"]!!.jsonPrimitive.content)
    }

    @Test
    fun readCustomTool_returnsStructuredErrorForUnknownName() = runTest {
        installRuntimeSettingsGatewayForTest()

        val resultJson = ReadCustomToolBuiltin().invokeRawJson(
            BuiltinToolRequest(
                name = "read_custom_tool",
                argumentsJson = """{"name":"missing_tool"}""",
            )
        )

        val json = Json.parseToJsonElement(resultJson).jsonObject
        assertFalse(json["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("CUSTOM_TOOL_NOT_FOUND", json["code"]!!.jsonPrimitive.content)
    }
}
