package com.niki914.zafiro.app.ui.chat.mapper

import com.niki914.zafiro.app.R
import com.niki914.zafiro.chat.LlmErrorCode

internal data class AssistantErrorUi(
    val titleRes: Int,
    val bodyRes: Int? = null,
    val body: String? = null,
)

internal fun toAssistantErrorUi(
    message: String?,
    code: LlmErrorCode?,
    attempts: Int? = null,
): AssistantErrorUi {
    return when (code) {
        // 配置问题：用户可行动的引导（唯一本地化正文）
        LlmErrorCode.ConfigRequired -> AssistantErrorUi(
            titleRes = R.string.ui_home_error_config_required_title,
            bodyRes = R.string.ui_home_error_config_required_body,
        )

        // 服务器/网络异常（用户选的服务器）：标题分类 + 原始 message 透传。
        // Parse 也归此类：400 系客户端错误（模型名无效等）是用户配置/服务器问题，
        // 与 Auth 同类，不应算内部错误（正文 message 给出具体原因）。
        // message 为空时由 bodyRes 兜底。
        // RetryExhausted 带 attempts：标题反映"重试已耗尽"而非泛网络错误
        LlmErrorCode.RetryExhausted if attempts != null -> AssistantErrorUi(
            titleRes = R.string.ui_home_error_retry_exhausted_title,
            bodyRes = if (message.isNullOrBlank()) R.string.ui_home_error_retry_body else null,
            body = message?.trim()?.ifEmpty { null },
        )

        LlmErrorCode.Auth, LlmErrorCode.Quota, LlmErrorCode.RateLimit,
        LlmErrorCode.Overloaded, LlmErrorCode.Transport, LlmErrorCode.Parse,
        LlmErrorCode.RetryExhausted,
            -> AssistantErrorUi(
            titleRes = R.string.ui_home_error_network_title,
            bodyRes = if (message.isNullOrBlank()) R.string.ui_home_error_retry_body else null,
            body = message?.trim()?.ifEmpty { null },
        )

        // 响应空闲超时：专属标题，秒数不进错误串（用户只需知道超时了）
        LlmErrorCode.IdleTimeout -> AssistantErrorUi(
            titleRes = R.string.ui_home_error_idle_timeout_title,
            bodyRes = if (message.isNullOrBlank()) R.string.ui_home_error_retry_body else null,
            body = message?.trim()?.ifEmpty { null },
        )

        // 输出被上限截断：正文告诉用户去哪里调大（新设置项），不说是网络/内部问题
        LlmErrorCode.OutputTruncated -> AssistantErrorUi(
            titleRes = R.string.ui_home_error_output_truncated_title,
            bodyRes = R.string.ui_home_error_output_truncated_body,
        )

        // 复读/退化熔断：模型输出陷入重复，框架主动中止。正文告知已中止与应对。
        LlmErrorCode.Repetition -> AssistantErrorUi(
            titleRes = R.string.ui_home_error_repetition_title,
            bodyRes = R.string.ui_home_error_repetition_body,
        )

        // 内部错误（我们的问题 / 未知）：标题分类 + 原始 message 透传，空则兜底
        LlmErrorCode.TurnConflict, LlmErrorCode.HookFailed,
        LlmErrorCode.ToolExecutionFailed, null,
            -> {
            val normalized = message?.trim()
            if (normalized.isNullOrEmpty()) {
                AssistantErrorUi(
                    titleRes = R.string.ui_home_error_internal_title,
                    bodyRes = R.string.ui_home_error_retry_body,
                )
            } else {
                AssistantErrorUi(
                    titleRes = R.string.ui_home_error_internal_title,
                    body = normalized,
                )
            }
        }
    }
}

internal fun toAssistantErrorUi(message: String?): AssistantErrorUi {
    return toAssistantErrorUi(message = message, code = null)
}
