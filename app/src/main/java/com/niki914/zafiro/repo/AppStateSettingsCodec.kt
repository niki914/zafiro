package com.niki914.zafiro.repo

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * 应用级开关与偏好的单文档聚合。文件位置见 StoreDescriptorRegistry.APP_STATE_ID。
 *
 * 序列化说明：字段声明顺序即写盘 key 顺序（与历史手写 encode 一致），
 * 缺 key 走默认值，未知 key（如已删除的旧字段）直接忽略，畸形 JSON 回全默认值。
 */
@Serializable
internal data class AppStateSettings(
    @SerialName("onboarding_completed")
    val onboardingCompleted: Boolean = false,
    @SerialName("last_opened_conversation_id")
    val lastOpenedConversationId: String = "",
    /** BCP-47 tag；空串 = 跟随系统语言。 */
    @SerialName("language_tag")
    val languageTag: String = "",
    /** 冷启动是否恢复上次会话；false = 默认进入新对话。 */
    @SerialName("load_last_conversation_on_startup")
    val loadLastConversationOnStartup: Boolean = false,
    /** 主题深浅色模式；system/light/dark。 */
    @SerialName("theme_mode")
    val themeMode: String = "dark",
    /** 主题种子色 ARGB hex；空串 = 跟随壁纸动态色。 */
    @SerialName("theme_seed_color")
    val themeSeedColor: String = "",
    /** 流式空闲超时秒数；0 = 不超时。 */
    @SerialName("llm_idle_timeout_seconds")
    val llmIdleTimeoutSeconds: Long = 60L,
    /** 传输层自动重试次数。 */
    @SerialName("llm_retry_max_attempts")
    val llmRetryMaxAttempts: Int = 3,
    /** 回答进行中保持屏幕常亮。 */
    @SerialName("keep_screen_on")
    val keepScreenOn: Boolean = true,
    /** 消息操作行（复制/重新生成/fork 等）是否常显。 */
    @SerialName("always_show_message_actions")
    val alwaysShowMessageActions: Boolean = true,
    /** 是否启用 Agent 悬浮球。 */
    @SerialName("floating_ball_enabled")
    val floatingBallEnabled: Boolean = false,
    /** 是否启用常驻通知栏。 */
    @SerialName("resident_notification_enabled")
    val residentNotificationEnabled: Boolean = false,
)

internal object AppStateSettingsCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun parse(jsonString: String): AppStateSettings {
        return try {
            // 显式 serializer：与 ConversationRepo 一致（reified 重载在本模块不可用）。
            json.decodeFromString(AppStateSettings.serializer(), jsonString).let {
                // 损坏文件里的空串兜底：setter 只写 system/light/dark。
                if (it.themeMode.isBlank()) it.copy(themeMode = "dark") else it
            }
        } catch (_: SerializationException) {
            AppStateSettings()
        } catch (_: IllegalArgumentException) {
            AppStateSettings()
        }
    }

    fun encode(state: AppStateSettings): String =
        json.encodeToString(AppStateSettings.serializer(), state)
}
