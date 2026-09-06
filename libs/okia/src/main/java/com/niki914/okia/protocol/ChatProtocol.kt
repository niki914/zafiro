package com.niki914.okia.protocol

import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.okia.message.ToolCallOutcome
import com.niki914.okia.transport.HttpRequest
import com.niki914.okia.transport.SseLine
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64

/**
 * 一种 LLM API 方言：构建请求并解析流。传输在库外，测试用 SseLine 流
 * 和 fake engine 驱动协议。
 * 协议 id 稳定但不进入会话数据：host 恢复时重新 open<P>() 提供 Provider。
 * id 与 defaultEndpoint 都是从 compat 取的（协议类不各自持有，withCodec
 * 后身份不变）。工具结果从共享 ToolCallOutcome 编码，isError 由 outcome 派生。
 * 内置协议：OpenAIChatCompletionProtocol（含 DeepSeek compat 形态）、
 * OpenAIResponsesProtocol、AnthropicMessagesProtocol。
 * Design source: pi api 层（openai-completions / openai-responses 拆分），
 * kai PRD §4.3；okia 骨架对照基线。
 */
interface ChatProtocol {

    // 稳定协议 id（如 "deepseek"），从 compat 取
    val id: String

    // 协议自带的默认端点（如 DeepSeek 官方 API），从 compat 取。调用方在
    // config.endpoint 显式设置时覆盖；两者皆空时 open() fail-fast（方案 A，
    // §8.17）。null = 协议不自带默认端点，调用方必须提供 endpoint。
    val defaultEndpoint: String?

    // 注入 JSON 编解码器（kotlinx.serialization 标准替代 JsonCodec）
    fun withCodec(codec: Json): ChatProtocol

    // apiKey → 认证头；apiKey 为空时返回空 map
    fun useApiKey(apiKey: String): Map<String, String>

    // 协议无关数据 → Provider 请求。history 包含当前输入（send 已先提交
    // User 消息），不存在独立的 pendingUserInput。
    // suspend：ImageLoader 读取走 IO dispatcher，buildRequest 内调用它。
    suspend fun buildRequest(
        snapshot: RequestSnapshot,
        history: List<Message>
    ): HttpRequest

    // 原始 SSE 流 → 协议无关中间事件
    fun parseStream(rawSseLines: Flow<SseLine>): Flow<ProtocolEvent>

    // 工具结果编码（isError 由 outcome 派生；Interrupted / Unknown 编码为错误文本）
    fun encodeToolResult(call: ContentBlock.ToolCall, outcome: ToolCallOutcome): Message

    // 协议兼容性事实
    val compat: Compat
}

/**
 * 工具结果图片逐张加载：成功 → (dataUrl, mimeType)，失败 → 文本注记。
 * 逐张降级（部分成功照发，失败进注记），注记由调用方拼进工具结果文本。
 * 共享于三协议的 tool result 图片编码。
 */
internal suspend fun loadToolImages(
    snapshot: RequestSnapshot,
    images: List<ContentBlock.Image>
): Pair<List<Pair<String, String>>, String> {
    if (images.isEmpty() || !snapshot.supportsImages) return emptyList<Pair<String, String>>() to ""
    val loader = snapshot.imageLoader ?: return emptyList<Pair<String, String>>() to ""
    val loaded = mutableListOf<Pair<String, String>>()
    val notes = mutableListOf<String>()
    for (image in images) {
        val bytes = loader.load(image.path)
        if (bytes != null) {
            loaded += "data:${image.mimeType};base64,${Base64.encode(bytes)}" to image.mimeType
        } else {
            notes += "[image omitted: file not found]"
        }
    }
    return loaded to notes.joinToString("\n")
}

/**
 * User 消息文本 + 全图降级注记拼接（supportsImages=false / loader 缺失的
 * 全降级路径）。text 非空时注记换行追加，避免原文与注记粘连。
 */
internal inline fun String.withImageNotes(
    images: List<ContentBlock.Image>,
    crossinline note: (ContentBlock.Image) -> String
): String {
    val notes = images.joinToString("\n") { note(it) }
    return if (isBlank()) notes else "$this\n$notes"
}
