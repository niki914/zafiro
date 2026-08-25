package com.niki914.zafiro.chat

import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.TextToolResult
import com.niki914.zafiro.chat.agentic.buildin.TextToolResultCodec
import com.niki914.zafiro.chat.agentic.buildin.impl.LoadSkillBuiltin
import com.niki914.zafiro.settings.RuntimeBridge
import com.niki914.zafiro.settings.RuntimeEnvironment
import com.niki914.zafiro.settings.RuntimeHostGateway
import com.niki914.zafiro.settings.RuntimeSettingsGateway
import com.niki914.zafiro.settings.model.RuntimeLoadedSkill
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadSkillBuiltinTest {
    @After
    fun tearDown() {
        RuntimeEnvironment.clearForTest()
    }

    @Test
    fun invoke_validJson_returnsSkillData() = runTest {
        installRuntimeSettingsGatewayForTest(
            FakeRuntimeSettingsGateway(
                loadedSkills = mapOf("skill-a" to loadedSkill("skill-a"))
            )
        )

        val result = invokeAndDecode("""{"id":"skill-a"}""")

        assertEquals(TextToolResult.Status.Success, result.status)
        assertEquals("Skill content A", result.payload)
    }

    @Test
    fun invoke_missingId_returnsFailure() = runTest {
        installRuntimeSettingsGatewayForTest()

        val result = invokeAndDecode("{}")

        assertEquals(TextToolResult.Status.Failure, result.status)
        assertEquals("MISSING_SKILL_ID", result.code)
        assertNotNull(result.message)
        assertTrue(result.message!!.contains("available_skills"))
    }

    @Test
    fun invoke_invalidJson_returnsFailure() = runTest {
        installRuntimeSettingsGatewayForTest()

        val result = invokeAndDecode("not-json")

        assertEquals(TextToolResult.Status.Failure, result.status)
        assertEquals("INVALID_ARGUMENTS_JSON", result.code)
        assertNotNull(result.message)
        assertTrue(result.message!!.contains("JSON"))
    }

    @Test
    fun invoke_missingSkill_returnsFailure() = runTest {
        installRuntimeSettingsGatewayForTest()

        val result = invokeAndDecode("""{"id":"missing"}""")

        assertEquals(TextToolResult.Status.Failure, result.status)
        assertEquals("SKILL_NOT_FOUND", result.code)
        assertNotNull(result.message)
        assertTrue(result.message!!.contains("missing"))
    }

    @Test
    fun invoke_disabledSkill_returnsFailure() = runTest {
        installRuntimeSettingsGatewayForTest(
            FakeRuntimeSettingsGateway(
                loadedSkills = mapOf("skill-a" to loadedSkill("skill-a", enabled = false))
            )
        )

        val result = invokeAndDecode("""{"id":"skill-a"}""")

        assertEquals(TextToolResult.Status.Failure, result.status)
        assertEquals("SKILL_DISABLED", result.code)
        assertNotNull(result.message)
        assertTrue(result.message!!.contains("disabled"))
    }

    @Test
    fun invoke_gatewayFailure_returnsFailure() = runTest {
        RuntimeEnvironment.install(
            RuntimeBridge(
                settings = FailingLoadSkillGateway,
                host = FakeRuntimeHostGatewayForLoadSkillTest,
            )
        )

        val result = invokeAndDecode("""{"id":"skill-a"}""")

        assertEquals(TextToolResult.Status.Failure, result.status)
        assertEquals("SETTINGS_READ_FAILED", result.code)
        assertNotNull(result.message)
        assertTrue(result.message!!.contains("settings"))
    }

    private suspend fun invokeAndDecode(argumentsJson: String): TextToolResult {
        val raw = LoadSkillBuiltin().invokeRawJson(
            BuiltinToolRequest(
                name = "load_skill",
                argumentsJson = argumentsJson,
            )
        )
        val decoded = TextToolResultCodec.decode(raw)
        assertNotNull("Expected #!tool-result protocol output, got: $raw", decoded)
        return decoded!!
    }

    private fun loadedSkill(
        id: String,
        enabled: Boolean = true,
    ): RuntimeLoadedSkill {
        return RuntimeLoadedSkill(
            id = id,
            name = "Skill A",
            description = "Description A",
            relativePath = "$id/SKILL.md",
            absolutePath = "/private/$id/SKILL.md",
            absoluteDir = "/private/$id",
            content = "Skill content A",
            enabled = enabled,
        )
    }

    private object FailingLoadSkillGateway :
        RuntimeSettingsGateway by FakeRuntimeSettingsGateway() {
        override suspend fun loadSkill(id: String): RuntimeLoadedSkill? {
            error("settings unavailable")
        }
    }

    private object FakeRuntimeHostGatewayForLoadSkillTest : RuntimeHostGateway {
        override suspend fun postNotification(
            title: String,
            content: String,
            uri: String?
        ): Boolean = false
    }
}
