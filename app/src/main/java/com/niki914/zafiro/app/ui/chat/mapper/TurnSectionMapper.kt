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
 * 2. 思考过程（Thinking）与工具调用（Tool）一视同仁，统一抽象为执行流中的“步骤（Step）”；
 * 3. 步骤以非空正文 Text 为天然封顶哨兵进行分段切分：
 *    - 流式进行中（未输出新正文且正在生成）：各步骤平铺外露（[TurnSection.Step]），保留转圈与展开态；
 *    - 正文到达或已完成/冷启动时：
 *      - 若步骤数 N = 1：单步穿透外露（[TurnSection.Step]），杜绝多余套娃；
 *      - 若步骤数 N >= 2：收纳为复合步骤块 [TurnSection.WrappedSteps]，默认呈现折叠态；
 * 4. 系统/网络错误映射为独立 [TurnSection.Error]，专属 tertiaryContainer 且不可折叠；
 * 5. 重试中状态映射为独立 [TurnSection.Retrying]，专属 secondaryContainer 且不可折叠。
 */
internal fun HomeChatTurn.toTurnUiModel(
    alwaysShowDetailed: Boolean = false,
    userToggledKeys: Map<String, Boolean> = emptyMap(),
    onToggleAction: (key: String, nextExpanded: Boolean) -> Unit = { _, _ -> },
    activeThinkingKey: String? = null,
    isGenerating: Boolean = false,
): TurnUiModel {
    val sections = mutableListOf<TurnSection>()
    val currentSteps = mutableListOf<ActionUiModel>()
    var wrappedIndex = 0

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

    fun flushSteps(hasSuccessorText: Boolean) {
        if (currentSteps.isEmpty()) return
        val isSealed = hasSuccessorText || !isGenerating

        if (!isSealed) {
            // 流式进行中且后续无正文：保持平铺，正在执行的卡片保持运行态
            for (step in currentSteps) {
                sections.add(
                    TurnSection.Step(
                        key = step.key,
                        action = step,
                    )
                )
            }
        } else {
            // 已被正文封顶或会话已结束/冷启动
            if (currentSteps.size == 1) {
                // N = 1: 单步穿透外露
                val singleStep = currentSteps.first()
                sections.add(
                    TurnSection.Step(
                        key = singleStep.key,
                        action = singleStep,
                    )
                )
            } else {
                // N >= 2: 复合步骤收纳包裹
                val wrapKey = "${id}_wrapped_$wrappedIndex"
                val isWrapExpanded = userToggledKeys[wrapKey] ?: false

                // 收纳块内子步骤默认呈收起单行态，仅当用户显式点击该子步骤时展开
                val wrappedSubSteps = currentSteps.map { action ->
                    val isSubExpanded = userToggledKeys[action.key] ?: false
                    action.copy(
                        isExpanded = isSubExpanded,
                        onToggle = { onToggleAction(action.key, !isSubExpanded) },
                    )
                }

                sections.add(
                    TurnSection.WrappedSteps(
                        key = wrapKey,
                        steps = wrappedSubSteps,
                        isExpanded = isWrapExpanded,
                        onToggle = { onToggleAction(wrapKey, !isWrapExpanded) },
                    )
                )
                wrappedIndex++
            }
        }
        currentSteps.clear()
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

                currentSteps.add(
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

            is HomeChatBlock.Thinking -> {
                val vmKey = "${id}_$blockIndex"
                val actionKey = "${id}_thinking_$blockIndex"
                val isRunning = !block.isComplete
                val isUserToggled = actionKey in userToggledKeys
                val defaultExpanded = !hasSuccessor(blockIndex)
                val isExpanded = userToggledKeys[actionKey] ?: defaultExpanded

                currentSteps.add(
                    ActionUiModel(
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
            }

            is HomeChatBlock.Text -> {
                if (block.text.isNotBlank()) {
                    flushSteps(hasSuccessorText = true)
                    sections.add(
                        TurnSection.Text(
                            key = "${id}_txt_$blockIndex",
                            text = block.text,
                        )
                    )
                }
            }

            is HomeChatBlock.Error -> {
                flushSteps(hasSuccessorText = false)
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
                flushSteps(hasSuccessorText = false)
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

    flushSteps(hasSuccessorText = false)

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
