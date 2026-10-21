package com.niki914.zafiro.app.ui.chat.mapper

import com.niki914.zafiro.app.ui.chat.model.ActionBodyUi
import com.niki914.zafiro.app.ui.model.ToolPresentation
import com.niki914.zafiro.app.ui.model.home.HomeToolState
import com.niki914.zafiro.app.ui.model.home.HomeToolStatus

private val CommandToolNames = setOf("shell", "ssh", "execute_python")

/**
 * 将工具原始状态 [HomeToolStatus] 转化为纯分流的 [ActionBodyUi]。
 *
 * 遵循策略：
 * ```
 * if (hasCustomizedStyle) {
 *     Custom(...) // Command, Image, Skill, Thinking 内部自决 UI 与按钮
 * } else {
 *     if (enableDetails) {
 *         Detailed(showCopyBtn = outputMeaningful)
 *     } else {
 *         Simple(...)
 *     }
 * }
 * ```
 */
internal fun HomeToolStatus.toActionBodyUi(alwaysShowDetailed: Boolean): ActionBodyUi {
    val isCustomPy = name == "py_meta_tools" && !inputText.isNullOrBlank()
    val useCommandBody = (name in CommandToolNames || isCustomPy) && !inputText.isNullOrBlank()

    // 1. 定制样式 (Customized Style)
    if (images.isNotEmpty()) {
        return ActionBodyUi.Custom.ImagePreview(images = images)
    }

    if (useCommandBody) {
        val inputPreview = ToolPresentation.previewOf(inputText).orEmpty()
        val text = if (alwaysShowDetailed) {
            resultText ?: failedReason
        } else {
            ToolPresentation.formatConciseOutput(name, resultText) ?: failedReason
        }
        val output = ToolPresentation.prettyPrintIfJson(text).orEmpty().trim()
        val isRunning = state == HomeToolState.Running
        return ActionBodyUi.Custom.Command(
            command = inputPreview,
            copyText = inputText,
            output = output,
            isError = state == HomeToolState.Failed,
            isRunning = isRunning,
        )
    }

    if (name == "load_skill") {
        val skillContent = (resultText ?: failedReason)?.trim().orEmpty()
        return ActionBodyUi.Custom.SkillContent(markdown = skillContent)
    }

    // 2. 通用样式 (Standard Style)
    if (alwaysShowDetailed) {
        val raw = (resultText ?: failedReason)?.trim()
        val formatted = ToolPresentation.prettyPrintIfJson(raw).orEmpty()
        val isMeaningful = formatted.isNotBlank()
        return ActionBodyUi.Standard.Detailed(
            text = formatted,
            isError = state == HomeToolState.Failed,
            showCopyButton = isMeaningful,
        )
    }

    // alwaysShowDetailed == false 简略模式：先提取 formatConciseOutput
    val conciseText = ToolPresentation.formatConciseOutput(name, resultText) ?: failedReason
    val formattedConcise = ToolPresentation.prettyPrintIfJson(conciseText)?.trim()

    return when {
        state == HomeToolState.Failed -> {
            ActionBodyUi.Standard.Simple(
                isFailed = true,
                isRunning = false,
                reason = failedReason ?: formattedConcise,
            )
        }
        !formattedConcise.isNullOrBlank() -> {
            ActionBodyUi.Standard.Detailed(
                text = formattedConcise,
                isError = false,
                showCopyButton = false,
            )
        }
        else -> {
            ActionBodyUi.Standard.Simple(
                isFailed = false,
                isRunning = state == HomeToolState.Running,
            )
        }
    }
}
