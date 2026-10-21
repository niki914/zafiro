package com.niki914.zafiro.app.ui.chat.model

import com.niki914.zafiro.app.ui.model.home.HomeChatFile
import com.niki914.zafiro.app.ui.model.home.HomeChatImage

/**
 * 回合级 UI 模型：严格区分用户输入与助手生成流。
 * 将一维平铺的 blocks 规整为结构化的 [TurnSection] 段落流。
 */
data class TurnUiModel(
    val id: Long,
    val userText: String = "",
    val userImages: List<HomeChatImage> = emptyList(),
    val userFiles: List<HomeChatFile> = emptyList(),
    val sections: List<TurnSection> = emptyList(),
    val isCompactedDivider: Boolean = false,
    val isCompacting: Boolean = false,
) {
    val hasUserContent: Boolean
        get() = userText.isNotBlank() || userImages.isNotEmpty() || userFiles.isNotEmpty()
}

/**
 * 助手段落：明确类型划分的结构化段落。
 * 包含：纯文本正文、独立思考过程卡片、工具链（单/多工具）、错误卡片与重试卡片。
 */
sealed interface TurnSection {
    val key: String

    /** 纯文本正文阶段 */
    data class Text(
        override val key: String,
        val text: String,
    ) : TurnSection

    /** 独立思考过程卡片（绝不与工具混叠聚类） */
    data class Thinking(
        override val key: String,
        val action: ActionUiModel,
    ) : TurnSection

    /** 工具链段落：连续的 1 个或多个工具调用 */
    data class ToolRun(
        override val key: String,
        val actions: List<ActionUiModel>,
        val isExpanded: Boolean = false,
        val onToggle: () -> Unit = {},
    ) : TurnSection

    /** 错误卡片 */
    data class Error(
        override val key: String,
        val action: ActionUiModel,
    ) : TurnSection

    /** 重试中卡片 */
    data class Retrying(
        override val key: String,
        val action: ActionUiModel,
    ) : TurnSection
}