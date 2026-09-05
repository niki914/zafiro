package com.niki914.okia.protocol

import com.niki914.okia.ImageLoader
import com.niki914.okia.tooling.ToolDescriptor
import com.niki914.okia.transport.HttpTimeouts
import com.niki914.okia.transport.redactHeaders

/**
 * 一次模型调用输入的不可变快照，段开始时冻结，重试复用同一请求。
 * 由 loop 从 config 与回合选项构建。
 * Design source: okia 骨架 RequestSnapshot（冻结快照模式）。
 */
data class RequestSnapshot(
    val endpoint: String,
    val apiKey: String,
    val model: String,
    val systemPrompt: String?,
    val temperature: Float,
    val maxTokens: Int,
    val headers: Map<String, String>,
    val timeouts: HttpTimeouts,
    val tools: List<ToolDescriptor>,
    val supportsImages: Boolean = false,
    val imageLoader: ImageLoader? = null
) {

    // apiKey 与敏感 header 值脱敏（systemPrompt 非凭据，保留）
    override fun toString(): String =
        "RequestSnapshot(endpoint=$endpoint, apiKey=██, model=$model, " +
                "systemPrompt=$systemPrompt, temperature=$temperature, maxTokens=$maxTokens, " +
                "headers=${redactHeaders(headers)}, timeouts=$timeouts, tools=$tools)"
}
