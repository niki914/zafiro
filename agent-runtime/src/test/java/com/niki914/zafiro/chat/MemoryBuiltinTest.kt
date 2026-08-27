package com.niki914.zafiro.chat

import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.impl.MemoryBuiltin
import com.niki914.zafiro.settings.RuntimeEnvironment
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

class MemoryBuiltinTest {
    @After
    fun tearDown() {
        RuntimeEnvironment.clearForTest()
    }

    @Test
    fun memory_addWritesTrimmedMemoryAndReturnsSuccessJson() = runTest {
        val store = installRuntimeSettingsGatewayForTest()

        val resultJson = MemoryBuiltin().invokeRawJson(
            BuiltinToolRequest(
                name = "memory",
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
    fun memory_addReturnsStructuredErrorForBlankContent() = runTest {
        installRuntimeSettingsGatewayForTest()

        val resultJson = MemoryBuiltin().invokeRawJson(
            BuiltinToolRequest(
                name = "memory",
                argumentsJson = """{"action":"add","content":"   "}""",
            )
        )

        val json = Json.parseToJsonElement(resultJson).jsonObject
        assertFalse(json["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("INVALID_ARGUMENTS", json["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun memory_missingActionReturnsError() = runTest {
        installRuntimeSettingsGatewayForTest()

        val resultJson = MemoryBuiltin().invokeRawJson(
            BuiltinToolRequest(
                name = "memory",
                argumentsJson = """{"content":"some fact"}""",
            )
        )

        val json = Json.parseToJsonElement(resultJson).jsonObject
        assertFalse(json["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("INVALID_ARGUMENTS", json["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun memory_unknownActionReturnsError() = runTest {
        installRuntimeSettingsGatewayForTest()

        val resultJson = MemoryBuiltin().invokeRawJson(
            BuiltinToolRequest(
                name = "memory",
                argumentsJson = """{"action":"remvoe","content":"typo"}""",
            )
        )

        val json = Json.parseToJsonElement(resultJson).jsonObject
        assertFalse(json["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("INVALID_ARGUMENTS", json["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun memory_explicitAddActionWorks() = runTest {
        val store = installRuntimeSettingsGatewayForTest()

        val resultJson = MemoryBuiltin().invokeRawJson(
            BuiltinToolRequest(
                name = "memory",
                argumentsJson = """{"action":"add","content":"explicit add"}""",
            )
        )

        val json = Json.parseToJsonElement(resultJson).jsonObject
        assertTrue(json["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("add", json["action"]!!.jsonPrimitive.content)
        assertEquals(listOf("explicit add"), store.memories)
    }

    @Test
    fun memory_removeDeletesByOldText() = runTest {
        val store = installRuntimeSettingsGatewayForTest()
        store.memories.addAll(listOf("keep", "delete-me-please", "also-keep"))

        val resultJson = MemoryBuiltin().invokeRawJson(
            BuiltinToolRequest(
                name = "memory",
                argumentsJson = """{"action":"remove","old_text":"delete-me"}""",
            )
        )

        val json = Json.parseToJsonElement(resultJson).jsonObject
        assertTrue(json["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("remove", json["action"]!!.jsonPrimitive.content)
        assertEquals(listOf("keep", "also-keep"), store.memories)
    }

    @Test
    fun memory_removeReturnsErrorForNotFound() = runTest {
        val store = installRuntimeSettingsGatewayForTest()
        store.memories.add("only")

        val resultJson = MemoryBuiltin().invokeRawJson(
            BuiltinToolRequest(
                name = "memory",
                argumentsJson = """{"action":"remove","old_text":"nonexistent"}""",
            )
        )

        val json = Json.parseToJsonElement(resultJson).jsonObject
        assertFalse(json["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("NOT_FOUND", json["code"]!!.jsonPrimitive.content)
        assertEquals(listOf("only"), store.memories)
    }

    @Test
    fun memory_removeReturnsErrorForAmbiguousMatch() = runTest {
        val store = installRuntimeSettingsGatewayForTest()
        store.memories.addAll(listOf("User prefers dark mode", "User prefers concise answers"))

        val resultJson = MemoryBuiltin().invokeRawJson(
            BuiltinToolRequest(
                name = "memory",
                argumentsJson = """{"action":"remove","old_text":"User prefers"}""",
            )
        )

        val json = Json.parseToJsonElement(resultJson).jsonObject
        assertFalse(json["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("AMBIGUOUS_MATCH", json["code"]!!.jsonPrimitive.content)
        assertEquals(2, store.memories.size)
    }

    @Test
    fun memory_replaceUpdatesEntry() = runTest {
        val store = installRuntimeSettingsGatewayForTest()
        store.memories.addAll(listOf("keep", "User prefers dark mode", "also-keep"))

        val resultJson = MemoryBuiltin().invokeRawJson(
            BuiltinToolRequest(
                name = "memory",
                argumentsJson = """{"action":"replace","old_text":"dark mode","content":"User prefers light mode"}""",
            )
        )

        val json = Json.parseToJsonElement(resultJson).jsonObject
        assertTrue(json["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("replace", json["action"]!!.jsonPrimitive.content)
        assertEquals(listOf("keep", "User prefers light mode", "also-keep"), store.memories)
    }

    @Test
    fun memory_replaceReturnsErrorForNotFound() = runTest {
        val store = installRuntimeSettingsGatewayForTest()
        store.memories.add("only")

        val resultJson = MemoryBuiltin().invokeRawJson(
            BuiltinToolRequest(
                name = "memory",
                argumentsJson = """{"action":"replace","old_text":"nonexistent","content":"new"}""",
            )
        )

        val json = Json.parseToJsonElement(resultJson).jsonObject
        assertFalse(json["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("NOT_FOUND", json["code"]!!.jsonPrimitive.content)
        assertEquals(listOf("only"), store.memories)
    }

    @Test
    fun memory_replaceReturnsErrorForAmbiguousMatch() = runTest {
        val store = installRuntimeSettingsGatewayForTest()
        store.memories.addAll(listOf("User prefers dark mode", "User prefers concise answers"))

        val resultJson = MemoryBuiltin().invokeRawJson(
            BuiltinToolRequest(
                name = "memory",
                argumentsJson = """{"action":"replace","old_text":"User prefers","content":"new pref"}""",
            )
        )

        val json = Json.parseToJsonElement(resultJson).jsonObject
        assertFalse(json["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("AMBIGUOUS_MATCH", json["code"]!!.jsonPrimitive.content)
        assertEquals(2, store.memories.size)
    }

    @Test
    fun memory_replaceAllowsDuplicateMatchWhenIdenticalContent() = runTest {
        val store = installRuntimeSettingsGatewayForTest()
        store.memories.addAll(listOf("same entry", "same entry"))

        val resultJson = MemoryBuiltin().invokeRawJson(
            BuiltinToolRequest(
                name = "memory",
                argumentsJson = """{"action":"replace","old_text":"same entry","content":"updated entry"}""",
            )
        )

        val json = Json.parseToJsonElement(resultJson).jsonObject
        assertTrue(json["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("replace", json["action"]!!.jsonPrimitive.content)
        assertEquals(listOf("updated entry", "same entry"), store.memories)
    }

}
