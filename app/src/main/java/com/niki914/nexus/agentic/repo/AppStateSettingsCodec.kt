package com.niki914.nexus.agentic.repo

import com.niki914.nexus.agentic.repo.SettingsJsonCodecUtils.boolean
import com.niki914.nexus.agentic.repo.SettingsJsonCodecUtils.parseObject
import com.niki914.nexus.agentic.repo.SettingsJsonCodecUtils.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal data class AppStateSettings(
    val onboardingCompleted: Boolean = false,
    val startupAssistantUi: String = "auto",
    val lastOpenedAgentId: String = "main",
    val lastOpenedConversationId: String = "",
)

internal object AppStateSettingsCodec {
    fun parse(json: String): AppStateSettings {
        val root = parseObject(json)
        return AppStateSettings(
            onboardingCompleted = root.boolean(ONBOARDING_COMPLETED_KEY, default = false),
            startupAssistantUi = root.string(STARTUP_ASSISTANT_UI_KEY).ifBlank { "auto" },
            lastOpenedAgentId = root.string(LAST_OPENED_AGENT_ID_KEY).ifBlank { "main" },
            lastOpenedConversationId = root.string(LAST_OPENED_CONVERSATION_ID_KEY),
        )
    }

    fun encode(state: AppStateSettings): String {
        return JsonObject(
            mapOf(
                ONBOARDING_COMPLETED_KEY to JsonPrimitive(state.onboardingCompleted),
                STARTUP_ASSISTANT_UI_KEY to JsonPrimitive(state.startupAssistantUi),
                LAST_OPENED_AGENT_ID_KEY to JsonPrimitive(state.lastOpenedAgentId),
                LAST_OPENED_CONVERSATION_ID_KEY to JsonPrimitive(state.lastOpenedConversationId),
            )
        ).toString()
    }

    private const val ONBOARDING_COMPLETED_KEY = "onboarding_completed"
    private const val STARTUP_ASSISTANT_UI_KEY = "startup_assistant_ui"
    private const val LAST_OPENED_AGENT_ID_KEY = "last_opened_agent_id"
    private const val LAST_OPENED_CONVERSATION_ID_KEY = "last_opened_conversation_id"
}
