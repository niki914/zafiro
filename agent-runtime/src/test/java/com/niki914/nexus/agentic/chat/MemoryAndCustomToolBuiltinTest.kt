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
    fun memorize_writesTrimmedMemoryAndReturnsSuccessJson() = runTest {
        val store = installRuntimeSettingsGatewayForTest()

        val resultJson = MemorizeBuiltin().invokeRawJson(
            BuiltinToolRequest(
                name = "memorize",
                argumentsJson = """{"content":"  User prefers concise answers.  "}""",
            )
        )

        val json = Json.parseToJsonElement(resultJson).jsonObject
        assertTrue(json["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("add", json["action"]!!.jsonPrimitive.content)
        assertEquals(listOf("User prefers concise answers."), store.memories)
        assertEquals(1, store.writeCount)
    }

    @Test
    fun memorize_returnsStructuredErrorForBlankContent() = runTest {
        installRuntimeSettingsGatewayForTest()

        val resultJson = MemorizeBuiltin().invokeRawJson(
            BuiltinToolRequest(
                name = "memorize",
                argumentsJson = """{"content":"   "}""",
            )
        )

        val json = Json.parseToJsonElement(resultJson).jsonObject
        assertFalse(json["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("INVALID_ARGUMENTS", json["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun memorize_listReturnsItemsAsJsonArray() = runTest {
        val store = installRuntimeSettingsGatewayForTest()
        store.memories.addAll(listOf("first", "second"))

        val resultJson = MemorizeBuiltin().invokeRawJson(
            BuiltinToolRequest(
                name = "memorize",
                argumentsJson = """{"action":"list"}""",
            )
        )

        val json = Json.parseToJsonElement(resultJson).jsonObject
        assertTrue(json["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("list", json["action"]!!.jsonPrimitive.content)
        val items = json["items"]!!.jsonArray
        assertEquals(2, items.size)
        assertEquals(0, items[0].jsonObject["index"]!!.jsonPrimitive.content.toInt())
        assertEquals("first", items[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals(1, items[1].jsonObject["index"]!!.jsonPrimitive.content.toInt())
        assertEquals("second", items[1].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun memorize_removeDeletesByIndex() = runTest {
        val store = installRuntimeSettingsGatewayForTest()
        store.memories.addAll(listOf("keep", "delete-me", "also-keep"))

        val resultJson = MemorizeBuiltin().invokeRawJson(
            BuiltinToolRequest(
                name = "memorize",
                argumentsJson = """{"action":"remove","index":1}""",
            )
        )

        val json = Json.parseToJsonElement(resultJson).jsonObject
        assertTrue(json["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("remove", json["action"]!!.jsonPrimitive.content)
        assertEquals(1, json["index"]!!.jsonPrimitive.content.toInt())
        assertEquals(listOf("keep", "also-keep"), store.memories)
    }

    @Test
    fun memorize_removeReturnsErrorForOutOfRangeIndex() = runTest {
        val store = installRuntimeSettingsGatewayForTest()
        store.memories.add("only")

        val resultJson = MemorizeBuiltin().invokeRawJson(
            BuiltinToolRequest(
                name = "memorize",
                argumentsJson = """{"action":"remove","index":5}""",
            )
        )

        val json = Json.parseToJsonElement(resultJson).jsonObject
        assertFalse(json["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("INDEX_OUT_OF_RANGE", json["code"]!!.jsonPrimitive.content)
        assertEquals(listOf("only"), store.memories)
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
