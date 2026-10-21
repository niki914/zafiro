package com.niki914.zafiro.app.ui.chat.model

import androidx.annotation.StringRes
import com.niki914.zafiro.app.ui.model.ToolPresentation

enum class ActionContainerTone {
    Default,
    Tertiary,
}

/**
 * 单个动作项（Thinking / Tool / Error / Retrying）的外壳元数据模型。
 * 供 [StandaloneActionCard] 统一消费。
 */
data class ActionUiModel(
    val key: String,
    val iconSource: ToolPresentation.IconSource = ToolPresentation.IconSource.Vector(ToolPresentation.Default),
    val title: String? = null,
    @param:StringRes val titleRes: Int? = null,
    val subtitlePreview: String? = null,
    val isRunning: Boolean = false,
    val isFailed: Boolean = false,
    val isExpanded: Boolean = false,
    val isUserToggled: Boolean = false,
    val isThinking: Boolean = false,
    val nonCollapsible: Boolean = false,
    val containerTone: ActionContainerTone = ActionContainerTone.Default,
    val body: ActionBodyUi,
    val onToggle: () -> Unit = {},
)

