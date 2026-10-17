// 保护：AgentSessionEngine 与 LLM 配置校验契约；模型与端点缺失快速失败
package com.niki914.zafiro.chat

import com.niki914.zafiro.settings.model.RuntimeLlmConfig
import org.junit.Assert.fail
import org.junit.Test

class AgentSessionEngineValidationTest {

    private val engine = AgentSessionEngine()

    @Test
    fun validateLlmConfig_throwsWhenEndpointIsBlank() {
        captureIllegalState {
            engine.validateLlmConfig(
                RuntimeLlmConfig(
                    endpoint = " ",
                    model = "deepseek-chat",
                )
            )
        }
    }

    @Test
    fun validateLlmConfig_throwsWhenModelIsBlank() {
        captureIllegalState {
            engine.validateLlmConfig(
                RuntimeLlmConfig(
                    endpoint = "https://example.com/v1",
                    model = "",
                )
            )
        }
    }

    private fun captureIllegalState(block: () -> Unit): IllegalStateException {
        return try {
            block()
            fail("expected IllegalStateException")
            throw IllegalStateException("unreachable")
        } catch (error: IllegalStateException) {
            error
        }
    }
}
