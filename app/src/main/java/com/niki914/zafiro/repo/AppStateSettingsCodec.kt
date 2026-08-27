package com.niki914.zafiro.repo

import com.niki914.zafiro.repo.SettingsJsonCodecUtils.boolean
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.parseObject
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal data class AppStateSettings(
    val onboardingCompleted: Boolean = false,
    val startupAssistantUi: String = "auto",
    val lastOpenedAgentId: String = "main",
    val lastOpenedConversationId: String = "",
    /** BCP-47 tag；空串 = 跟随系统语言。 */
    val languageTag: String = "",
    /** 冷启动是否恢复上次会话；false = 默认进入新对话。 */
    val loadLastConversationOnStartup: Boolean = false,
)

internal object AppStateSettingsCodec {
    fun parse(json: String): AppStateSettings {
        val root = parseObject(json)
        return AppStateSettings(
            onboardingCompleted = root.boolean(ONBOARDING_COMPLETED_KEY, default = false),
            startupAssistantUi = root.string(STARTUP_ASSISTANT_UI_KEY).ifBlank { "auto" },
            lastOpenedAgentId = root.string(LAST_OPENED_AGENT_ID_KEY).ifBlank { "main" },
            lastOpenedConversationId = root.string(LAST_OPENED_CONVERSATION_ID_KEY),
            languageTag = root.string(LANGUAGE_TAG_KEY),
            loadLastConversationOnStartup = root.boolean(
                LOAD_LAST_CONVERSATION_KEY,
                default = false
            ),
        )
    }

    fun encode(state: AppStateSettings): String {
        return JsonObject(
            mapOf(
                ONBOARDING_COMPLETED_KEY to JsonPrimitive(state.onboardingCompleted),
                STARTUP_ASSISTANT_UI_KEY to JsonPrimitive(state.startupAssistantUi),
                LAST_OPENED_AGENT_ID_KEY to JsonPrimitive(state.lastOpenedAgentId),
                LAST_OPENED_CONVERSATION_ID_KEY to JsonPrimitive(state.lastOpenedConversationId),
                LANGUAGE_TAG_KEY to JsonPrimitive(state.languageTag),
                LOAD_LAST_CONVERSATION_KEY to JsonPrimitive(state.loadLastConversationOnStartup),
            )
        ).toString()
    }

    private const val ONBOARDING_COMPLETED_KEY = "onboarding_completed"
    private const val STARTUP_ASSISTANT_UI_KEY = "startup_assistant_ui"
    private const val LAST_OPENED_AGENT_ID_KEY = "last_opened_agent_id"
    private const val LAST_OPENED_CONVERSATION_ID_KEY = "last_opened_conversation_id"
    private const val LANGUAGE_TAG_KEY = "language_tag"
    private const val LOAD_LAST_CONVERSATION_KEY = "load_last_conversation_on_startup"
}
