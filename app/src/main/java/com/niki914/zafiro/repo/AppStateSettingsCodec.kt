package com.niki914.zafiro.repo

import com.niki914.zafiro.settings.model.RepetitionSensitivity
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
const val DEFAULT_COMPACTION_THRESHOLD_PERCENT: Int = 70

@Serializable
internal data class AppStateSettings(
    @SerialName("onboarding_completed")
    val onboardingCompleted: Boolean = false,
    @SerialName("last_opened_conversation_id")
    val lastOpenedConversationId: String = "",
    /** BCP-47 tag；空串 = 跟随系统语言。 */
    @SerialName("language_tag")
    val languageTag: String = "",
    /** 冷启动是否恢复上次会话；true = 默认进入上次会话。 */
    @SerialName("load_last_conversation_on_startup")
    val loadLastConversationOnStartup: Boolean = true,
    /** 主题深浅色模式；system/light/dark。 */
    @SerialName("theme_mode")
    val themeMode: String = "light",
    /** 主题种子色 ARGB hex；空串 = 跟随壁纸动态色。 */
    @SerialName("theme_seed_color")
    val themeSeedColor: String = "FFFABD00",
    /** Markdown 字体排版缩放比例；基准为 1.0f (100%)。 */
    @SerialName("markdown_scale")
    val markdownScale: Float = 1.0f,
    /** 流式空闲超时秒数；0 = 不超时。 */
    @SerialName("llm_idle_timeout_seconds")
    val llmIdleTimeoutSeconds: Long = 60L,
    /** 传输层自动重试次数。 */
    @SerialName("llm_retry_max_attempts")
    val llmRetryMaxAttempts: Int = 3,
    /** 复读检测灵敏度档位（RepetitionSensitivity.wireValue）。 */
    @SerialName("repetition_sensitivity")
    val repetitionSensitivity: String = "loose",
    /** 上下文自动压缩阈值百分比（30..95，0 = 关闭，默认 70）。 */
    @SerialName("context_compaction_threshold_percent")
    val contextCompactionThresholdPercent: Int = DEFAULT_COMPACTION_THRESHOLD_PERCENT,
    /** 回答进行中保持屏幕常亮。 */
    @SerialName("keep_screen_on")
    val keepScreenOn: Boolean = true,
    /** 消息操作行（复制/重新生成/fork 等）是否常显。 */
    @SerialName("always_show_message_actions")
    val alwaysShowMessageActions: Boolean = true,
    /** 是否总是展示工具调用的完整详细输出。 */
    @SerialName("always_show_detailed_tool_output")
    val alwaysShowDetailedToolOutput: Boolean = false,
    /** 是否启用 Agent 悬浮球。 */
    @SerialName("floating_ball_enabled")
    val floatingBallEnabled: Boolean = false,
    /** 是否启用常驻通知栏。 */
    @SerialName("resident_notification_enabled")
    val residentNotificationEnabled: Boolean = false,
    /** 是否启用回合事件通知（完成/需要审批时发送即时横幅）。 */
    @SerialName("turn_notification_enabled")
    val turnNotificationEnabled: Boolean = false,
    /** 悬浮球在回合结束/审批到达时是否自动展开。 */
    @SerialName("floating_ball_auto_expand")
    val floatingBallAutoExpand: Boolean = true,
    /** 已经向用户弹过更新提示的远端版本号；空串 = 从未弹过。同一版本只提示一次。 */
    @SerialName("last_notified_update_version")
    val lastNotifiedUpdateVersion: String = "",
    /** 语音助手独享一个会话；true = 独立会话，false = 绑定主界面会话。默认开启。 */
    @SerialName("voice_assistant_isolated_session")
    val voiceAssistantIsolatedSession: Boolean = true,
    /** 置顶会话：id + 置顶时刻。数量级 ≤ 20，读取方按会话存在性自行剪枝。 */
    @SerialName("pinned_conversations")
    val pinnedConversations: List<PinnedConversation> = emptyList(),
    /** 全局输入框草稿文本（跨会话/新建会话保持，冷启动自动恢复）。 */
    @SerialName("draft_text")
    val draftText: String = "",
)

/** 一条置顶记录：置顶时刻用于与最后交互时间取 max 排序（越晚置顶/越晚交互越靠前）。 */
@Serializable
data class PinnedConversation(
    @SerialName("id")
    val id: String = "",
    @SerialName("pinned_at")
    val pinnedAt: Long = 0L,
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
                // 损坏文件里的兜底：themeMode 空串回到 light；markdown_scale 越界夹回可用区间。
                val fixed = if (it.themeMode.isBlank()) it.copy(themeMode = "light") else it
                // 灵敏度是开放字符串：未知值（手改 / 拼错 / 其他版本写入）回默认档，不崩。
                val sanitizedCompactionThreshold = when {
                    fixed.contextCompactionThresholdPercent == 0 -> 0
                    else -> fixed.contextCompactionThresholdPercent.coerceIn(30, 95)
                }
                fixed.copy(
                    markdownScale = fixed.markdownScale.coerceIn(0.6f, 1.4f),
                    repetitionSensitivity = RepetitionSensitivity.fromWire(
                        fixed.repetitionSensitivity
                    ).wireValue,
                    contextCompactionThresholdPercent = sanitizedCompactionThreshold,
                )
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
