package com.niki914.zafiro.app.ui.content

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.niki914.uikit.infra.ActionBarButton
import com.niki914.okia.message.ThinkingLevel
import com.niki914.uikit.infra.component.SettingToggleItem
import com.niki914.uikit.infra.component.SettingsGroupCard
import com.niki914.uikit.infra.component.SettingsItemDivider
import com.niki914.uikit.infra.component.SettingsListItem
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.model.ConfigureUiState
import com.niki914.zafiro.settings.model.LlmProtocol

@Composable
internal fun ConfigureIdentitySettingsBlock(
    uiState: ConfigureUiState,
    showNameField: Boolean,
    fieldController: EditableDetailFieldController<ConfigureEditableField>,
    onNameChange: (String) -> Unit,
    onProxyChange: (String) -> Unit,
) {
    SettingsGroupCard {
        if (showNameField) {
            SettingControlledExpandableTextItem(
                field = ConfigureEditableField.Name,
                controller = fieldController,
                title = stringResource(R.string.ui_settings_configure_name_label),
                value = uiState.configNameInput,
                onValueChange = onNameChange,
                placeholder = stringResource(R.string.ui_settings_configure_name_placeholder),
                description = uiState.nameErrorResId?.let { stringResource(it) },
                enabled = !uiState.isSaving,
                minLines = 1,
                maxLines = 1,
            )
            SettingsItemDivider()
        }
        SettingControlledExpandableTextItem(
            field = ConfigureEditableField.Proxy,
            controller = fieldController,
            title = stringResource(R.string.ui_settings_configure_proxy_label),
            value = uiState.proxyInput,
            onValueChange = onProxyChange,
            placeholder = stringResource(R.string.ui_settings_configure_proxy_placeholder),
            description = uiState.proxyErrorResId?.let { stringResource(it) },
            enabled = !uiState.isSaving,
            minLines = 1,
            maxLines = 1,
        )
    }
}

@Composable
internal fun ConfigureConnectionSettingsBlock(
    uiState: ConfigureUiState,
    policy: ConfigurePagePolicy,
    fieldController: EditableDetailFieldController<ConfigureEditableField>,
    onEndpointOverrideChange: (Boolean) -> Unit,
    onEndpointChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onToggleApiKeyVisibility: () -> Unit,
    onShowModelCatalogSheet: () -> Unit = {},
) {
    val onClearActiveField = fieldController.clearActiveField
    SettingsGroupCard {
        // 填写顺序：API Key → Model；Model 有目录时挤左 + 右侧按钮
        SettingControlledExpandableTextItem(
            field = ConfigureEditableField.ApiKey,
            controller = fieldController,
            title = stringResource(R.string.ui_onboard_configure_api_key_label),
            value = uiState.apiKeyInput,
            onValueChange = onApiKeyChange,
            placeholder = stringResource(R.string.ui_onboard_configure_api_key_placeholder),
            description = uiState.apiKeyErrorResId?.let { stringResource(it) },
            enabled = !uiState.isSaving,
            minLines = 1,
            maxLines = 1,
            secretVisible = uiState.apiKeyVisible,
            onToggleSecretVisibility = onToggleApiKeyVisibility,
            toggleSecretVisibleContentDescription = stringResource(
                R.string.ui_onboard_configure_api_key_show,
            ),
            toggleSecretHiddenContentDescription = stringResource(
                R.string.ui_onboard_configure_api_key_hide,
            ),
        )
        SettingsItemDivider()
        // 双模式：目录为空 = 输入框占满（现状）；非空 = 输入框挤左 + 右侧按钮
        val catalogButton: (@Composable () -> Unit)? =
            if (uiState.modelCatalog.isEmpty()) {
                null
            } else {
                {
                    ModelCatalogButton(
                        enabled = !uiState.isSaving,
                        onClick = {
                            onClearActiveField()
                            onShowModelCatalogSheet()
                        },
                    )
                }
            }
        SettingControlledExpandableTextItem(
            field = ConfigureEditableField.Model,
            controller = fieldController,
            title = stringResource(R.string.ui_onboard_configure_model_label),
            value = uiState.modelInput,
            onValueChange = onModelChange,
            placeholder = stringResource(R.string.ui_onboard_configure_model_placeholder),
            description = uiState.modelErrorResId?.let { stringResource(it) }
                ?: if (uiState.modelInput.isBlank()) {
                    stringResource(
                        R.string.ui_onboard_configure_model_example,
                        uiState.providerSpec.exampleModelId,
                    )
                } else {
                    null
                },
            enabled = !uiState.isSaving,
            minLines = 1,
            maxLines = 1,
            fieldTrailingContent = catalogButton,
        )
        SettingsItemDivider()
        if (policy.showEndpointSection) {
            val endpointEditable = policy.endpointEditable &&
                (!policy.showEndpointOverrideToggle || uiState.endpointOverrideEnabled)
            SettingControlledExpandableTextItem(
                field = ConfigureEditableField.Endpoint,
                controller = fieldController,
                title = stringResource(R.string.ui_onboard_configure_endpoint_label),
                value = uiState.endpointInput,
                onValueChange = onEndpointChange,
                placeholder = stringResource(R.string.ui_onboard_configure_endpoint_placeholder),
                description = uiState.endpointErrorResId?.let { stringResource(it) },
                enabled = endpointEditable && !uiState.isSaving,
                minLines = 3,
                maxLines = 6,
            )
            if (policy.showEndpointOverrideToggle) {
                SettingsItemDivider()
                SettingToggleItem(
                    title = stringResource(R.string.ui_onboard_configure_endpoint_override_title),
                    description = stringResource(
                        if (uiState.endpointOverrideEnabled) {
                            R.string.ui_onboard_configure_endpoint_override_description_on
                        } else {
                            R.string.ui_onboard_configure_endpoint_override_description_off
                        },
                    ),
                    checked = uiState.endpointOverrideEnabled,
                    enabled = !uiState.isSaving,
                    onCheckedChange = { enabled ->
                        if (!uiState.isSaving) {
                            onClearActiveField()
                            onEndpointOverrideChange(enabled)
                        }
                    },
                )
            }
            SettingsItemDivider()
        }
    }
}

/** 模型目录按钮：复用 LiquidScreen 左右按钮同款（Home 添加图片按钮同款）。 */
@Composable
private fun ModelCatalogButton(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    // add 按钮恒亮：不随 isSaving 变灰（只有输入框随保存态禁用）
    CompositionLocalProvider(
        LocalContentColor provides MaterialTheme.colorScheme.primary,
    ) {
        ActionBarButton(
            onClick = onClick,
            enabled = enabled,
        ) {
            Icon(
                imageVector = Icons.Default.FormatListBulleted,
                contentDescription = stringResource(
                    R.string.ui_onboard_configure_model_catalog_open
                ),
            )
        }
    }
}

@Composable
internal fun ConfigureProtocolSettingsBlock(
    uiState: ConfigureUiState,
    fieldController: EditableDetailFieldController<ConfigureEditableField>,
    onSupportsImagesChange: (Boolean) -> Unit,
    onProtocolSelected: (String) -> Unit,
    onThinkingLevelSelected: (String) -> Unit,
) {
    val onClearActiveField = fieldController.clearActiveField
    var showProtocolDialog by rememberSaveable { mutableStateOf(false) }
    var showThinkingDialog by rememberSaveable { mutableStateOf(false) }

    SettingsGroupCard {
        SettingToggleItem(
            title = stringResource(R.string.ui_settings_configure_vision_label),
            checked = uiState.supportsImages,
            enabled = !uiState.isSaving,
            onCheckedChange = { enabled ->
                if (!uiState.isSaving) {
                    onClearActiveField()
                    onSupportsImagesChange(enabled)
                }
            },
        )
        SettingsItemDivider()
        // 协议行：Value Row（点击弹选择弹窗）
        SettingsListItem(
            title = stringResource(R.string.ui_settings_configure_protocol_label),
            currentState = uiState.protocolWireId,
            showChevron = true,
            onClick = {
                onClearActiveField()
                showProtocolDialog = true
            },
        )
        SettingsItemDivider()
        // 思考强度行：Value Row（点击弹单选弹窗）
        SettingsListItem(
            title = stringResource(R.string.ui_settings_configure_thinking_label),
            currentState = thinkingLevelLabel(uiState.thinkingLevelWire),
            showChevron = true,
            onClick = {
                onClearActiveField()
                showThinkingDialog = true
            },
        )
    }

    // "deepseek" 协议与 openai-chat-completions 同壳，仅作存量存储值兼容，不再提供新选
    val selectableProtocols = LlmProtocol.entries.filter { it != LlmProtocol.DeepSeek }
    SingleChoiceLiquidDialog(
        visible = showProtocolDialog,
        onDismissRequest = { showProtocolDialog = false },
        title = stringResource(R.string.ui_settings_configure_protocol_label),
        hint = stringResource(R.string.ui_settings_configure_protocol_hint),
        options = selectableProtocols,
        selectedId = uiState.protocolWireId,
        optionId = LlmProtocol::wireId,
        optionLabel = LlmProtocol::wireId,
        onSelect = { protocol ->
            showProtocolDialog = false
            onProtocolSelected(protocol.wireId)
        },
    )

    // 思考强度弹窗：含「Provider 默认」（不发字段）+ 全部 level，全协议同一可选项
    SingleChoiceLiquidDialog(
        visible = showThinkingDialog,
        onDismissRequest = { showThinkingDialog = false },
        title = stringResource(R.string.ui_settings_configure_thinking_label),
        hint = stringResource(R.string.ui_settings_configure_thinking_hint),
        options = thinkingLevelOptions(),
        selectedId = uiState.thinkingLevelWire,
        optionId = { it.wire },
        optionLabel = { it.label },
        onSelect = { option ->
            showThinkingDialog = false
            onThinkingLevelSelected(option.wire)
        },
    )
}

/** 思考强度可选项：「Provider 默认」（空串 = 不发字段）+ 全部 ThinkingLevel。 */
private data class ThinkingLevelOption(val wire: String, val label: String)

private fun thinkingLevelOptions(): List<ThinkingLevelOption> =
    listOf(ThinkingLevelOption("", "Provider Default")) +
        ThinkingLevel.entries.map { ThinkingLevelOption(it.wireValue, it.wireValue) }

private fun thinkingLevelLabel(wire: String): String =
    thinkingLevelOptions().firstOrNull { it.wire == wire }?.label ?: "Provider Default"
