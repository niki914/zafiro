package com.niki914.zafiro.app.ui.settings.form

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import com.niki914.uikit.infra.component.SettingExpandableTextItem

/**
 * 编辑详情页字段展开与焦点状态控制器。
 */
data class EditableDetailFieldController<Field>(
    val expandedField: Field?,
    val onExpandedFieldChange: (Field?) -> Unit,
    val clearActiveField: () -> Unit,
)

/**
 * 记住并绑定表单字段控制器生命周期。
 *
 * @param requestedFocusField 外部（如保存校验失败时）请求展开焦点的字段。
 * @param onRequestedFocusHandled 外部请求焦点被响应后的回调。
 */
@Composable
fun <Field : Enum<Field>> rememberEditableDetailFieldController(
    requestedFocusField: Field?,
    onRequestedFocusHandled: () -> Unit,
): EditableDetailFieldController<Field> {
    val focusManager = LocalFocusManager.current
    var expandedField by rememberSaveable { mutableStateOf<Field?>(null) }

    fun clearActiveField() {
        expandedField = null
        focusManager.clearFocus()
    }

    LaunchedEffect(requestedFocusField) {
        if (requestedFocusField != null) {
            expandedField = requestedFocusField
            onRequestedFocusHandled()
        }
    }

    return EditableDetailFieldController(
        expandedField = expandedField,
        onExpandedFieldChange = { field ->
            expandedField = field
        },
        clearActiveField = ::clearActiveField,
    )
}

/**
 * 受控的可展开文本输入设置项。
 */
@Composable
fun <Field : Enum<Field>> SettingControlledExpandableTextItem(
    field: Field,
    controller: EditableDetailFieldController<Field>,
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    description: String? = null,
    enabled: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = 1,
    secretVisible: Boolean = false,
    onToggleSecretVisibility: (() -> Unit)? = null,
    toggleSecretVisibleContentDescription: String? = null,
    toggleSecretHiddenContentDescription: String? = null,
    fieldTrailingContent: (@Composable () -> Unit)? = null,
) {
    SettingExpandableTextItem(
        title = title,
        value = value,
        onValueChange = onValueChange,
        placeholder = placeholder,
        description = description,
        enabled = enabled,
        expanded = controller.expandedField == field,
        minLines = minLines,
        maxLines = maxLines,
        secretVisible = secretVisible,
        onToggleSecretVisibility = onToggleSecretVisibility,
        toggleSecretVisibleContentDescription = toggleSecretVisibleContentDescription,
        toggleSecretHiddenContentDescription = toggleSecretHiddenContentDescription,
        onExpandedChange = { expanded ->
            controller.onExpandedFieldChange(if (expanded) field else null)
        },
        fieldTrailingContent = fieldTrailingContent,
    )
}

/**
 * 直接绑定 [FormFieldSpec] 的受控文本输入设置项。
 * 标题自动由 [FormFieldSpec.labelRes] 解析，字段与控制器自动绑定。
 */
@Composable
fun <State, Field : Enum<Field>> SettingControlledExpandableTextItem(
    spec: FormFieldSpec<State, Field>,
    controller: EditableDetailFieldController<Field>,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    description: String? = null,
    enabled: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = 1,
    secretVisible: Boolean = false,
    onToggleSecretVisibility: (() -> Unit)? = null,
    toggleSecretVisibleContentDescription: String? = null,
    toggleSecretHiddenContentDescription: String? = null,
    fieldTrailingContent: (@Composable () -> Unit)? = null,
) {
    SettingControlledExpandableTextItem(
        field = spec.id,
        controller = controller,
        title = stringResource(spec.labelRes),
        value = value,
        onValueChange = onValueChange,
        placeholder = placeholder,
        description = description,
        enabled = enabled,
        minLines = minLines,
        maxLines = maxLines,
        secretVisible = secretVisible,
        onToggleSecretVisibility = onToggleSecretVisibility,
        toggleSecretVisibleContentDescription = toggleSecretVisibleContentDescription,
        toggleSecretHiddenContentDescription = toggleSecretHiddenContentDescription,
        fieldTrailingContent = fieldTrailingContent,
    )
}
