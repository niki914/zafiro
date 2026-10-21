package com.niki914.zafiro.app.ui.settings.form

import androidx.compose.runtime.Composable
import com.niki914.uikit.infra.component.SettingsDetailFormScaffold

/**
 * 设置详情页表单脚手架。
 *
 * 结合 [rememberEditableDetailFieldController] 和 [SettingsDetailFormScaffold]，
 * 统一承载底部保存按钮、加载状态控制、错误文案展示以及背景点击收起键盘逻辑。
 */
@Composable
fun <Field : Enum<Field>> EditableSettingsDetailFormScaffold(
    actionText: String,
    requestedFocusField: Field?,
    onRequestedFocusHandled: () -> Unit,
    onActionClick: () -> Unit,
    description: String? = null,
    inlineErrorText: String? = null,
    actionEnabled: Boolean = true,
    content: @Composable (EditableDetailFieldController<Field>) -> Unit,
) {
    val fieldController = rememberEditableDetailFieldController(
        requestedFocusField = requestedFocusField,
        onRequestedFocusHandled = onRequestedFocusHandled,
    )

    SettingsDetailFormScaffold(
        actionText = actionText,
        onActionClick = {
            fieldController.clearActiveField()
            onActionClick()
        },
        description = description,
        inlineErrorText = inlineErrorText,
        actionEnabled = actionEnabled,
        onBackgroundTap = fieldController.clearActiveField,
    ) {
        content(fieldController)
    }
}
