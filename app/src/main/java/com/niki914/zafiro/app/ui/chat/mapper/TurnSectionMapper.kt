package com.niki914.zafiro.app.ui.chat.mapper

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.chat.model.ActionBodyUi
import com.niki914.zafiro.app.ui.chat.model.ActionContainerTone
import com.niki914.zafiro.app.ui.chat.model.ActionUiModel
import com.niki914.zafiro.app.ui.chat.model.TurnSection
import com.niki914.zafiro.app.ui.chat.model.TurnUiModel
import com.niki914.zafiro.app.ui.model.ToolPresentation
import com.niki914.zafiro.app.ui.model.home.HomeChatBlock
import com.niki914.zafiro.app.ui.model.home.HomeChatTurn
import com.niki914.zafiro.app.ui.model.home.HomeToolState

/**
 * 将平铺的 [HomeChatTurn] 映射为结构化、纯分流的 [TurnUiModel]。
 *
 * 核心对齐原则：
 * 1. 助手文本正文映射为 [TurnSection.Text]；
 * 2. 思考过程映射为独立 [TurnSection.Thinking]，绝不与工具混合聚类；
 * 3. 连续工具块映射为 [TurnSection.ToolRun]（内部包含 1 个或多个工具动作）；
 * 4. 系统/网络错误映射为独立 [TurnSection.Error]，专属 tertiaryContainer 且不可折叠；
 * 5. 重试中状态映射为独立 [TurnSection.Retrying]，专属 secondaryContainer 且不可折叠；
 * 6. 运行中（isRunning）的工具允许用户点击展开，若未手动收起默认可展开查看命令。
 */
internal fun HomeChatTurn.toTurnUiModel(
    alwaysShowDetailed: Boolean = false,
    userToggledKeys: Map<String, Boolean> = emptyMap(),
    onToggleAction: (key: String, nextExpanded: Boolean) -> Unit = { _, _ -> },
    activeThinkingKey: String? = null,
): TurnUiModel {
    val sections = mutableListOf<TurnSection>()
    val currentTools = mutableListOf<ActionUiModel>()
    var toolRunIndex = 0

    fun hasSuccessor(index: Int): Boolean {
        for (i in (index + 1) until blocks.size) {
            when (val b = blocks[i]) {
                is HomeChatBlock.Tool -> return true
                is HomeChatBlock.Thinking -> return true
                is HomeChatBlock.Error -> return true
                is HomeChatBlock.Retrying -> return true
                is HomeChatBlock.Text -> if (b.text.isNotBlank()) return true
            }
        }
        return false
    }

    fun flushTools() {
        if (currentTools.isEmpty()) return
        val runKey = "${id}_tools_$toolRunIndex"
        val isRunExpanded = userToggledKeys[runKey] ?: currentTools.any { it.isExpanded }
        sections.add(
            TurnSection.ToolRun(
                key = runKey,
                actions = currentTools.toList(),
                isExpanded = isRunExpanded,
                onToggle = { onToggleAction(runKey, !isRunExpanded) },
            )
        )
        currentTools.clear()
        toolRunIndex++
    }

    for ((blockIndex, block) in blocks.withIndex()) {
        when (block) {
            is HomeChatBlock.Tool -> {
                val actionKey = "${id}_tool_${block.status.callId ?: blockIndex}"
                val isRunning = block.status.state == HomeToolState.Running
                val bodyUi = block.status.toActionBodyUi(alwaysShowDetailed = alwaysShowDetailed)
                val inputPreview = ToolPresentation.previewOf(block.status.inputText)
                val isCommand = bodyUi is ActionBodyUi.Custom.Command
                val isUserToggled = actionKey in userToggledKeys
                val defaultExpanded = !hasSuccessor(blockIndex)
                val isExpanded = userToggledKeys[actionKey] ?: defaultExpanded

                currentTools.add(
                    ActionUiModel(
                        key = actionKey,
                        iconSource = ToolPresentation.iconSourceForTool(block.status.name),
                        title = if (block.status.displayNameRes == null) block.status.name else null,
                        titleRes = block.status.displayNameRes,
                        subtitlePreview = if (!isCommand) inputPreview else null,
                        isRunning = isRunning,
                        isFailed = block.status.state == HomeToolState.Failed,
                        isExpanded = isExpanded,
                        isUserToggled = isUserToggled,
                        isThinking = false,
                        body = bodyUi,
                        onToggle = { onToggleAction(actionKey, !isExpanded) },
                    )
                )
            }

            is HomeChatBlock.Text -> {
                flushTools()
                if (block.text.isNotBlank()) {
                    sections.add(
                        TurnSection.Text(
                            key = "${id}_txt_$blockIndex",
                            text = block.text,
                        )
                    )
                }
            }

            is HomeChatBlock.Thinking -> {
                flushTools()
                val vmKey = "${id}_$blockIndex"
                val actionKey = "${id}_thinking_$blockIndex"
                val isRunning = !block.isComplete
                val isUserToggled = actionKey in userToggledKeys
                val defaultExpanded = !hasSuccessor(blockIndex)
                val isExpanded = userToggledKeys[actionKey] ?: defaultExpanded

                sections.add(
                    TurnSection.Thinking(
                        key = actionKey,
                        action = ActionUiModel(
                            key = actionKey,
                            iconSource = ToolPresentation.ThinkingSource,
                            titleRes = R.string.ui_home_thinking_title,
                            subtitlePreview = ToolPresentation.previewOf(block.text),
                            isRunning = isRunning,
                            isFailed = false,
                            isExpanded = isExpanded,
                            isUserToggled = isUserToggled,
                            isThinking = true,
                            body = ActionBodyUi.Custom.Thinking(
                                text = block.text,
                                isComplete = block.isComplete,
                                autoScrollToEnd = isExpanded && vmKey == activeThinkingKey,
                            ),
                            onToggle = { onToggleAction(actionKey, !isExpanded) },
                        )
                    )
                )
            }

            is HomeChatBlock.Error -> {
                flushTools()
                val actionKey = "${id}_error_$blockIndex"
                val errorUi = toAssistantErrorUi(message = block.message, code = block.code, attempts = block.attempts)
                val formattedBody = ToolPresentation.prettyPrintIfJson(errorUi.body)
                val isMonospace = formattedBody?.let {
                    it.contains('\n') || it.startsWith('{') || it.startsWith('[')
                } ?: false
                sections.add(
                    TurnSection.Error(
                        key = actionKey,
                        action = ActionUiModel(
                            key = actionKey,
                            iconSource = ToolPresentation.IconSource.Vector(Icons.Filled.ErrorOutline),
                            titleRes = errorUi.titleRes,
                            isRunning = false,
                            isFailed = true,
                            isExpanded = true,
                            isThinking = false,
                            nonCollapsible = true,
                            containerTone = ActionContainerTone.Tertiary,
                            body = ActionBodyUi.Custom.ErrorDetail(
                                title = "",
                                body = formattedBody,
                                isMonospace = isMonospace,
                                showCopyButton = !formattedBody.isNullOrBlank(),
                            ),
                            onToggle = {},
                        )
                    )
                )
            }

            is HomeChatBlock.Retrying -> {
                flushTools()
                val actionKey = "${id}_retrying_$blockIndex"
                sections.add(
                    TurnSection.Retrying(
                        key = actionKey,
                        action = ActionUiModel(
                            key = actionKey,
                            iconSource = ToolPresentation.IconSource.Vector(Icons.Filled.Refresh),
                            titleRes = R.string.ui_home_retrying_title,
                            isRunning = true,
                            isFailed = false,
                            isExpanded = true,
                            isThinking = false,
                            nonCollapsible = true,
                            containerTone = ActionContainerTone.Tertiary,
                            body = ActionBodyUi.Custom.Retrying(
                                attempt = block.attempt,
                                maxAttempts = block.maxAttempts,
                                reason = block.reason,
                            ),
                            onToggle = {},
                        )
                    )
                )
            }
        }
    }

    flushTools()

    return TurnUiModel(
        id = id,
        userText = userText,
        userImages = images,
        userFiles = files,
        sections = sections,
        isCompactedDivider = isCompactedDivider,
        isCompacting = isCompacting,
    )
}
