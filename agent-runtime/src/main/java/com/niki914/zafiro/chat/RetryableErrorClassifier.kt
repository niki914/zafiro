package com.niki914.zafiro.chat

import com.niki914.okia.error.LLMError
import com.niki914.okia.error.LLMErrorCode

/**
 * 错误分类器：okia 的 LLMError → 更细的 Zafiro LlmErrorCode。
 *
 * okia 的 Transport 是个杂项桶（socket hang up / ECONNRESET / 5xx 全在里面），
 * UI 标题只能笼统归"网络异常"。这里按错误原文正则细分（对齐 pi
 * provider-retry 的模式表），让 Overloaded / RateLimit / Timeout 各自可辨。
 *
 * 只影响展示分类：okia 传输层的重试决策仍由它自己的 RetryPolicy 决定，
 * 本分类器不做任何重试判断。错误原文透传不替换。
 */
object RetryableErrorClassifier {

    // 匹配顺序即优先级：specific → generic。全小写比对。
    private val patterns: List<Pair<Regex, LlmErrorCode>> = listOf(
        // 服务过载
        Regex("overloaded|server is (?:over)?busy|temporarily unavailable|please try again later") to LlmErrorCode.Overloaded,
        // 限流
        Regex("\\brate limit\\b|\\b429\\b|too many requests") to LlmErrorCode.RateLimit,
        // 配额/计费（不重试类，单列让 UI 可区分）
        Regex("insufficient_quota|billing|\\bquota\\b") to LlmErrorCode.Quota,
        // 鉴权
        Regex("invalid[\\s_-]*api[\\s_-]*key|unauthorized|\\b401\\b|\\b403\\b|authentication") to LlmErrorCode.Auth,
        // 流式截断（上游断流，SSE 提前结束）
        Regex("stream ended without|stream (?:was )?(?:terminated|closed)|incomplete chunked encoding") to LlmErrorCode.Transport,
        // 空闲/请求超时（okia 的框架级 IdleTimeout 走独立事件，此处匹配传输层超时）
        Regex("\\btimeout\\b|timed?\\s*-?\\s*out|\\betimedout\\b|\\b408\\b") to LlmErrorCode.IdleTimeout,
        // 连接类
        Regex("socket hang up|econnreset|econnrefused|enotfound|eai_again|connection (?:error|refused|reset|closed)|network") to LlmErrorCode.Transport,
        // 5xx 服务器错误
        Regex("\\b5\\d{2}\\b") to LlmErrorCode.Overloaded,
    )

    /**
     * 分类 okia 错误。okia 自带的细粒度 code 直接透传；Transport（杂项桶）
     * 按原文正则细分（message + statusCode + cause 链）。
     */
    fun classify(error: LLMError): LlmErrorCode = when (error.code) {
        LLMErrorCode.Auth -> LlmErrorCode.Auth
        LLMErrorCode.Quota -> LlmErrorCode.Quota
        LLMErrorCode.RateLimit -> LlmErrorCode.RateLimit
        LLMErrorCode.Overloaded -> LlmErrorCode.Overloaded
        LLMErrorCode.ContextOverflow -> LlmErrorCode.Parse
        LLMErrorCode.Parse -> LlmErrorCode.Parse
        LLMErrorCode.HookFailed -> LlmErrorCode.HookFailed
        LLMErrorCode.ToolExecutionFailed -> LlmErrorCode.ToolExecutionFailed
        LLMErrorCode.RetryExhausted -> LlmErrorCode.RetryExhausted
        LLMErrorCode.Transport -> classifyByPattern(error)
    }

    private fun classifyByPattern(error: LLMError): LlmErrorCode {
        val haystack = buildString {
            append(error.message)
            error.statusCode?.let { append(" ").append(it) }
            error.cause?.message?.let { append(" ").append(it) }
        }.lowercase()
        patterns.forEach { (regex, code) ->
            if (regex.containsMatchIn(haystack)) return code
        }
        return LlmErrorCode.Transport
    }
}
