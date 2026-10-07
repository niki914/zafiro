package com.niki914.zafiro.runtime.client

import com.niki914.zafiro.runtime.ipc.RenderFrame
import com.niki914.zafiro.runtime.ipc.ToolItem

/**
 * 宿主助手端消费的纯语义流式数据帧（Sealed Interface）。
 *
 * 与底层的 Binder IPC 实体类 [RenderFrame] 完全解耦。
 * 在 [AgentRuntimeClient.submit] 中将 IPC 帧转换为此领域模型后，暴露给宿主 Hook 层消费。
 */
sealed interface AssistantFrame {
    val isFirst: Boolean
    val isFinal: Boolean

    /**
     * 正常的流式数据更新帧，包含思考、工具、正文 3 要素。
     */
    data class Update(
        val content: String = "",
        val thinking: Thinking? = null,
        val tools: List<ToolItem> = emptyList(),
        override val isFirst: Boolean = false,
        override val isFinal: Boolean = false,
    ) : AssistantFrame {
        data class Thinking(
            val text: String,
            val isComplete: Boolean,
        )
    }

    /**
     * 异常或失败帧。
     */
    data class Error(
        val message: String,
        override val isFirst: Boolean = false,
        override val isFinal: Boolean = true,
    ) : AssistantFrame
}

/**
 * 将底层的 IPC [RenderFrame] 映射为领域层 [AssistantFrame]。
 */
fun RenderFrame.toAssistantFrame(): AssistantFrame {
    val thinkingObj = if (!thinking.isNullOrBlank()) {
        AssistantFrame.Update.Thinking(
            text = thinking,
            isComplete = isThinkingComplete,
        )
    } else null

    return AssistantFrame.Update(
        content = content,
        thinking = thinkingObj,
        tools = tools,
        isFirst = isFirst,
        isFinal = isFinal,
    )
}
