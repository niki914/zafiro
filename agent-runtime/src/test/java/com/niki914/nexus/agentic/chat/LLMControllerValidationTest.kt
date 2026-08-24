package com.niki914.nexus.agentic.chat

import com.niki914.nexus.agentic.runtime.settings.model.RuntimeLlmConfig
import org.junit.Assert.fail
import org.junit.Test

class LLMControllerValidationTest {

    @Test
    fun validateLlmConfig_throwsWhenEndpointIsBlank() {
        captureIllegalState {
            LLMController.validateLlmConfig(
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
            LLMController.validateLlmConfig(
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
