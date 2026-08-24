package com.niki914.nexus.agentic.app.ui.nexus.content

import com.niki914.nexus.agentic.app.R
import com.niki914.nexus.agentic.chat.LlmErrorCode
import org.junit.Assert.assertEquals
import org.junit.Test

class AssistantErrorUiFormatterTest {

    @Test
    fun toAssistantErrorUi_formatsConfigRequiredMessage() {
        assertEquals(
            AssistantErrorUi(
                titleRes = R.string.ui_home_error_config_required_title,
                bodyRes = R.string.ui_home_error_config_required_body,
            ),
            toAssistantErrorUi("请先填写配置", LlmErrorCode.ConfigRequired),
        )
    }

    @Test
    fun toAssistantErrorUi_usesNetworkTitleForServerErrors() {
        assertEquals(
            AssistantErrorUi(
                titleRes = R.string.ui_home_error_network_title,
                body = "Connection refused",
            ),
            toAssistantErrorUi("Connection refused", LlmErrorCode.Transport),
        )
    }

    @Test
    fun toAssistantErrorUi_networkBlankMessageHasNoBody() {
        assertEquals(
            AssistantErrorUi(
                titleRes = R.string.ui_home_error_network_title,
                body = null,
            ),
            toAssistantErrorUi("  ", LlmErrorCode.RateLimit),
        )
    }

    @Test
    fun toAssistantErrorUi_parseGoesToNetworkBucket() {
        assertEquals(
            AssistantErrorUi(
                titleRes = R.string.ui_home_error_network_title,
                body = "The supported API model names are DeepSeek...",
            ),
            toAssistantErrorUi(
                "The supported API model names are DeepSeek...",
                LlmErrorCode.Parse,
            ),
        )
    }

    @Test
    fun toAssistantErrorUi_usesInternalTitleForOtherErrors() {
        assertEquals(
            AssistantErrorUi(
                titleRes = R.string.ui_home_error_internal_title,
                body = "boom",
            ),
            toAssistantErrorUi("boom", LlmErrorCode.HookFailed),
        )
    }

    @Test
    fun toAssistantErrorUi_fallsBackForBlankInternalMessage() {
        assertEquals(
            AssistantErrorUi(
                titleRes = R.string.ui_home_error_internal_title,
                bodyRes = R.string.ui_home_error_retry_body,
            ),
            toAssistantErrorUi(" "),
        )
    }
}
