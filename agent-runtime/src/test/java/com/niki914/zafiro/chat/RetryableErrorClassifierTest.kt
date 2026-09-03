package com.niki914.zafiro.chat

import com.niki914.okia.error.LLMError
import com.niki914.okia.error.LLMErrorCode
import org.junit.Assert.assertEquals
import org.junit.Test

class RetryableErrorClassifierTest {

    private fun classify(
        code: LLMErrorCode,
        message: String,
        statusCode: Int? = null,
        cause: Throwable? = null,
    ): LlmErrorCode = RetryableErrorClassifier.classify(LLMError(code, message, cause, statusCode))

    @Test
    fun `okia fine-grained codes pass through unchanged`() {
        assertEquals(LlmErrorCode.Auth, classify(LLMErrorCode.Auth, "invalid key"))
        assertEquals(LlmErrorCode.Quota, classify(LLMErrorCode.Quota, "quota"))
        assertEquals(LlmErrorCode.RateLimit, classify(LLMErrorCode.RateLimit, "slow down"))
        assertEquals(LlmErrorCode.Overloaded, classify(LLMErrorCode.Overloaded, "busy"))
        assertEquals(LlmErrorCode.Parse, classify(LLMErrorCode.Parse, "bad json"))
        assertEquals(
            LlmErrorCode.Parse,
            classify(LLMErrorCode.ContextOverflow, "context too long"),
        )
        assertEquals(LlmErrorCode.RetryExhausted, classify(LLMErrorCode.RetryExhausted, "retry exhausted (Transport)"))
        assertEquals(LlmErrorCode.HookFailed, classify(LLMErrorCode.HookFailed, "hook"))
        assertEquals(
            LlmErrorCode.ToolExecutionFailed,
            classify(LLMErrorCode.ToolExecutionFailed, "tool"),
        )
    }

    @Test
    fun `Transport overloads classify as Overloaded`() {
        assertEquals(
            LlmErrorCode.Overloaded,
            classify(LLMErrorCode.Transport, "The server is overloaded"),
        )
        assertEquals(
            LlmErrorCode.Overloaded,
            classify(LLMErrorCode.Transport, "service temporarily unavailable"),
        )
        assertEquals(LlmErrorCode.Overloaded, classify(LLMErrorCode.Transport, "upstream error", statusCode = 503))
    }

    @Test
    fun `Transport rate limits classify as RateLimit`() {
        assertEquals(LlmErrorCode.RateLimit, classify(LLMErrorCode.Transport, "rate limit exceeded"))
        assertEquals(LlmErrorCode.RateLimit, classify(LLMErrorCode.Transport, "HTTP 429", statusCode = 429))
        assertEquals(LlmErrorCode.RateLimit, classify(LLMErrorCode.Transport, "too many requests"))
    }

    @Test
    fun `Transport timeouts classify as IdleTimeout`() {
        assertEquals(LlmErrorCode.IdleTimeout, classify(LLMErrorCode.Transport, "request timeout"))
        assertEquals(LlmErrorCode.IdleTimeout, classify(LLMErrorCode.Transport, "connection timed out"))
        assertEquals(LlmErrorCode.IdleTimeout, classify(LLMErrorCode.Transport, "ETIMEDOUT", cause = RuntimeException("ETIMEDOUT")))
    }

    @Test
    fun `Transport connection errors classify as Transport`() {
        assertEquals(LlmErrorCode.Transport, classify(LLMErrorCode.Transport, "socket hang up"))
        assertEquals(LlmErrorCode.Transport, classify(LLMErrorCode.Transport, "ECONNRESET", cause = RuntimeException("ECONNRESET")))
        assertEquals(LlmErrorCode.Transport, classify(LLMErrorCode.Transport, "connection refused"))
    }

    @Test
    fun `Transport stream truncation classifies as Transport`() {
        assertEquals(
            LlmErrorCode.Transport,
            classify(LLMErrorCode.Transport, "stream ended without a final response"),
        )
    }

    @Test
    fun `auth keywords in transport message classify as Auth`() {
        assertEquals(LlmErrorCode.Auth, classify(LLMErrorCode.Transport, "HTTP 401", statusCode = 401))
        assertEquals(LlmErrorCode.Auth, classify(LLMErrorCode.Transport, "invalid api key"))
    }

    @Test
    fun `unrecognized transport message stays Transport`() {
        assertEquals(LlmErrorCode.Transport, classify(LLMErrorCode.Transport, "something weird happened"))
    }
}
