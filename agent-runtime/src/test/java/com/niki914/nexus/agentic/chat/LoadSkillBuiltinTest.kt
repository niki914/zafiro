package com.niki914.nexus.agentic.chat

import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.nexus.agentic.chat.agentic.buildin.impl.LoadSkillBuiltin
import com.niki914.nexus.agentic.runtime.settings.RuntimeBridge
import com.niki914.nexus.agentic.runtime.settings.RuntimeEnvironment
import com.niki914.nexus.agentic.runtime.settings.RuntimeHostGateway
import com.niki914.nexus.agentic.runtime.settings.RuntimeSettingsGateway
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeLoadedSkill
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

        val json = invoke("""{"id":"skill-a"}""")

        assertTrue(json["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("OK", json["code"]!!.jsonPrimitive.content)
        val data = json["data"]!!.jsonObject
        assertEquals("Skill A", data["skill_name"]!!.jsonPrimitive.content)
        assertEquals("skill-a/SKILL.md", data["skill_path"]!!.jsonPrimitive.content)
        assertEquals("Skill content A", data["skill_content"]!!.jsonPrimitive.content)
    }

    @Test
    fun invoke_skillIdAlias_returnsSkillData() = runTest {
        installRuntimeSettingsGatewayForTest(
            FakeRuntimeSettingsGateway(
                loadedSkills = mapOf("skill-a" to loadedSkill("skill-a"))
            )
        )

        val json = invoke("""{"skill_id":"skill-a"}""")

        assertEquals("OK", json["code"]!!.jsonPrimitive.content)
        assertEquals(
            "Skill content A",
            json["data"]!!.jsonObject["skill_content"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun invoke_missingId_returnsFailure() = runTest {
        installRuntimeSettingsGatewayForTest()

        val json = invoke("{}")

        assertFalse(json["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("MISSING_SKILL_ID", json["code"]!!.jsonPrimitive.content)
        assertTrue(json["field_errors"]!!.jsonObject.containsKey("id"))
    }

    @Test
    fun invoke_invalidJson_returnsFailure() = runTest {
        installRuntimeSettingsGatewayForTest()

        val json = invoke("not-json")

        assertFalse(json["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("INVALID_ARGUMENTS_JSON", json["code"]!!.jsonPrimitive.content)
        assertTrue(json["field_errors"]!!.jsonObject.containsKey("argumentsJson"))
    }

    @Test
    fun invoke_missingSkill_returnsFailure() = runTest {
        installRuntimeSettingsGatewayForTest()

        val json = invoke("""{"id":"missing"}""")

        assertFalse(json["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("SKILL_NOT_FOUND", json["code"]!!.jsonPrimitive.content)
        assertTrue(json["field_errors"]!!.jsonObject.containsKey("id"))
    }

    @Test
    fun invoke_disabledSkill_returnsFailure() = runTest {
        installRuntimeSettingsGatewayForTest(
            FakeRuntimeSettingsGateway(
                loadedSkills = mapOf("skill-a" to loadedSkill("skill-a", enabled = false))
            )
        )

        val json = invoke("""{"id":"skill-a"}""")

        assertFalse(json["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("SKILL_DISABLED", json["code"]!!.jsonPrimitive.content)
        assertTrue(json["field_errors"]!!.jsonObject.containsKey("id"))
    }

    @Test
    fun invoke_gatewayFailure_returnsFailure() = runTest {
        RuntimeEnvironment.install(
            RuntimeBridge(
                settings = FailingLoadSkillGateway,
                host = FakeRuntimeHostGatewayForLoadSkillTest,
            )
        )

        val json = invoke("""{"id":"skill-a"}""")

        assertFalse(json["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("SETTINGS_READ_FAILED", json["code"]!!.jsonPrimitive.content)
    }

    private suspend fun invoke(argumentsJson: String) = Json.parseToJsonElement(
        LoadSkillBuiltin().invoke(
            BuiltinToolRequest(
                name = "load_skill",
                argumentsJson = argumentsJson,
            )
        ).toJsonString()
    ).jsonObject

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
