package com.niki914.okia.message

import kotlinx.serialization.Serializable

/**
 * 消息内的一个内容单元。助手消息持有块列表，思考、文本与工具调用
 * 共处于单条响应（Anthropic 风格）。ToolResult 是消息类型而非块。
 * Design source: pi（packages/ai types.ts）content block union。
 */
@Serializable
sealed interface ContentBlock {

    /** 纯文本输出，带可选的 Provider 签名用于回放。 */
    @Serializable
    data class Text(val text: String, val signature: String? = null) : ContentBlock

    /** 模型思考，与最终文本分离。 */
    @Serializable
    data class Thinking(val text: String, val signature: String? = null) : ContentBlock

    /** Base64 编码图像。多模态入口，M2 前不支持。 */
    @Serializable
    data class Image(val data: String, val mimeType: String) : ContentBlock

    /** 模型发出的工具调用。参数保持 JSON 字符串。 */
    @Serializable
    data class ToolCall(
        val id: String,
        val name: String,
        val argumentsJson: String
    ) : ContentBlock
}
