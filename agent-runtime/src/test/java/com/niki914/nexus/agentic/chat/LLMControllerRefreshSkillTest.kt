package com.niki914.nexus.agentic.chat

import android.content.Context
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeLlmConfig
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeSkillMetadata
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class LLMControllerRefreshSkillTest {
    @Test
    fun refresh_passesEnabledSkillsIntoPrompt() = runTest {
        val gateway = installRuntimeSettingsGatewayForTest(
            FakeRuntimeSettingsGateway(
                llmConfig = validLlmConfig(),
                enabledSkills = listOf(
                    skill(
                        id = "skill-a",
                        name = "Skill A",
                        description = "Description A",
                    )
                ),
            )
        )

        val snapshot = LLMController.refresh()

        assertEquals(1, gateway.listEnabledSkillsCallCount)
        assertTrue(snapshot.prompt.finalSystemPrompt.contains("<available_skills>"))
        assertTrue(snapshot.prompt.finalSystemPrompt.contains("<id>skill-a</id>"))
    }

    @Test
    fun refresh_handlesEmptySkillList() = runTest {
        val gateway = installRuntimeSettingsGatewayForTest(
            FakeRuntimeSettingsGateway(llmConfig = validLlmConfig())
        )

        val snapshot = LLMController.refresh()

        assertEquals(1, gateway.listEnabledSkillsCallCount)
        assertFalse(snapshot.prompt.finalSystemPrompt.contains("## Skill Context"))
        assertFalse(snapshot.prompt.finalSystemPrompt.contains("<available_skills>"))
    }

    @Test
    fun stream_reusesPreviousSnapshotWhenSkillListFails() = runTest {
        val gateway = installRuntimeSettingsGatewayForTest(
            FakeRuntimeSettingsGateway(
                llmConfig = validLlmConfig(),
                enabledSkills = listOf(skill(id = "skill-a", name = "Skill A")),
            )
        )
        val previous = LLMController.refresh()
        gateway.failListEnabledSkills = IllegalStateException("skills failed")

        val context = mock(Context::class.java).apply {
            `when`(getString(com.niki914.nexus.agentic.runtime.R.string.error_llm_request_failed))
                .thenReturn("Request failed")
        }
        val firstEvent = withTimeoutOrNull(1_000) {
            LLMController.stream("q", context).firstOrNull()
        }

        assertEquals(2, gateway.listEnabledSkillsCallCount)
        assertEquals(previous, LLMController.snapshot())
        val error = firstEvent as? LlmStreamEvent.Error
        assertFalse(error?.message?.contains("skills failed") == true)
    }

    private fun validLlmConfig(): RuntimeLlmConfig {
        return RuntimeLlmConfig(
            endpoint = "https://example.com",
            model = "test-model",
            prompt = "Base prompt",
        )
    }

    private fun skill(
        id: String,
        name: String,
        description: String = "",
    ): RuntimeSkillMetadata {
        return RuntimeSkillMetadata(
            id = id,
            name = name,
            description = description,
            relativePath = "skills/$id/SKILL.md",
            absolutePath = "/private/skills/$id/SKILL.md",
            absoluteDir = "/private/skills/$id",
            enabled = true,
        )
    }
}
