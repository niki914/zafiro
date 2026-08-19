package com.niki914.nexus.agentic.chat

import android.content.Context
import android.content.ContextWrapper
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinTool
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolExecutor
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolRegistry
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolResult
import com.niki914.nexus.agentic.chat.agentic.buildin.RawJsonBuiltinTool
import com.niki914.nexus.agentic.chat.agentic.buildin.TextToolResult
import com.niki914.nexus.agentic.chat.agentic.buildin.TextToolResultCodec
import com.niki914.nexus.agentic.chat.agentic.buildin.impl.LoadSkillBuiltin
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeLoadedSkill
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class BuiltinToolExecutorTest {
    private val context: Context = ContextWrapper(null)

    @Test
    fun execute_invokesBuiltinWithArgumentsJson() = runTest {
        val tool = RecordingBuiltinTool("create_custom_tool")
        val executor = BuiltinToolExecutor(BuiltinToolRegistry(listOf(tool)))

        val resultJson = executor.execute(
            name = "create_custom_tool",
            argumentsJson = """{"name":"battery_status"}""",
        )

        assertEquals("create_custom_tool", tool.lastRequest?.name)
        assertEquals("""{"name":"battery_status"}""", tool.lastRequest?.argumentsJson)
        val json = Json.parseToJsonElement(resultJson).jsonObject
        assertEquals("OK", json["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun execute_returnsLocalToolNotExecutableWhenBuiltinMissing() = runTest {
        val executor = BuiltinToolExecutor(BuiltinToolRegistry(emptyList()))

        val resultJson = executor.execute("missing", "{}")

        val json = Json.parseToJsonElement(resultJson).jsonObject
        assertEquals("LOCAL_TOOL_NOT_EXECUTABLE", json["code"]!!.jsonPrimitive.content)
        assertEquals(
            "Check builtin_tool_flags or custom_tools configuration.",
            json["hint"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun execute_wrapsNonCancellationExceptionAsUnknownError() = runTest {
        val executor = BuiltinToolExecutor(
            BuiltinToolRegistry(
                listOf(
                    ThrowingBuiltinTool(
                        "broken",
                        IllegalStateException("boom")
                    )
                )
            )
        )

        val resultJson = executor.execute("broken", "{}")

        val json = Json.parseToJsonElement(resultJson).jsonObject
        assertEquals("UNKNOWN_ERROR", json["code"]!!.jsonPrimitive.content)
        assertEquals("boom", json["message"]!!.jsonPrimitive.content)
    }

    @Test
    fun execute_returnsRawJsonForRawJsonBuiltin() = runTest {
        val executor = BuiltinToolExecutor(
            BuiltinToolRegistry(listOf(RawJsonBuiltin("raw_json_tool")))
        )

        val resultJson = executor.execute("raw_json_tool", """{"command":"pwd"}""")

        val json = Json.parseToJsonElement(resultJson).jsonObject
        assertEquals("0", json["exit_code"]!!.jsonPrimitive.content)
        assertEquals("/", json["stdout"]!!.jsonPrimitive.content)
    }

    @Test
    fun execute_loadSkillBuiltin_usesBuiltinToolPath() = runTest {
        installRuntimeSettingsGatewayForTest(
            FakeRuntimeSettingsGateway(
                loadedSkills = mapOf(
                    "skill-a" to RuntimeLoadedSkill(
                        id = "skill-a",
                        name = "Skill A",
                        description = "Description A",
                        relativePath = "skills/skill-a/SKILL.md",
                        absolutePath = "/private/skills/skill-a/SKILL.md",
                        absoluteDir = "/private/skills/skill-a",
                        content = "# Skill A",
                        enabled = true,
                    )
                )
            )
        )
        val executor = BuiltinToolExecutor(BuiltinToolRegistry(listOf(LoadSkillBuiltin())))

        val resultRaw = executor.execute("load_skill", """{"id":"skill-a"}""")

        val result = TextToolResultCodec.decode(resultRaw)
        assertNotNull("Expected #!tool-result protocol output", result)
        assertEquals(TextToolResult.Status.Success, result!!.status)
        assertEquals("# Skill A", result.payload)
    }

    @Test(expected = CancellationException::class)
    fun execute_rethrowsCancellationException() = runTest {
        val executor = BuiltinToolExecutor(
            BuiltinToolRegistry(
                listOf(
                    ThrowingBuiltinTool(
                        "cancel",
                        CancellationException("cancel")
                    )
                )
            )
        )

        executor.execute("cancel", "{}")
    }

    private class RecordingBuiltinTool(
        override val name: String,
    ) : BuiltinTool() {
        var lastRequest: BuiltinToolRequest? = null


        override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
            lastRequest = request
            return BuiltinToolResult.success(message = "ok")
        }
    }

    private class ThrowingBuiltinTool(
        override val name: String,
        private val throwable: Throwable,
    ) : BuiltinTool() {

        override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
            throw throwable
        }
    }

    private class RawJsonBuiltin(
        override val name: String,
    ) : BuiltinTool(), RawJsonBuiltinTool {

        override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
            error("should not call invoke() for RawJsonBuiltinTool")
        }

        override suspend fun invokeRawJson(request: BuiltinToolRequest): String {
            return """{"exit_code":0,"stdout":"/"}"""
        }
    }
}
