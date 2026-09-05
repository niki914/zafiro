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

    /** 模型思考，与最终文本分离。signature：Anthropic/Gemini 块签名（原样回带）；
     *  opaquePayload：协议私有的不可解释数据（如 OpenAI reasoning item 的完整
     *  envelope），必须持久化但只由对应协议解析，其他协议不得读取。 */
    @Serializable
    data class Thinking(
        val text: String,
        val signature: String? = null,
        val opaquePayload: String? = null
    ) : ContentBlock

    /**
     * 图像引用。存储文件路径（非 base64），发送时由 protocol 经 ImageLoader
     * 读取并转为 base64。统一存储路径：/sdcard/Download/Zafiro/images/。
     */
    @Serializable
    data class Image(val path: String, val mimeType: String) : ContentBlock

    /** 模型发出的工具调用。参数保持 JSON 字符串。signature：Gemini 3 思维内
     *  工具调用的 thoughtSignature（回放时原样带回到 functionCall part）。 */
    @Serializable
    data class ToolCall(
        val id: String,
        val name: String,
        val argumentsJson: String,
        val signature: String? = null
    ) : ContentBlock
}
