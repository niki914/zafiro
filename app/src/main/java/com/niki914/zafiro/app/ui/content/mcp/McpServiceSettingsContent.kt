package com.niki914.zafiro.app.ui.content.mcp

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import com.niki914.uikit.infra.component.SettingToggleItem
import com.niki914.uikit.infra.component.SettingsGroupCard
import com.niki914.uikit.infra.component.SettingsItemDivider
import com.niki914.uikit.infra.component.SettingsListItem
import com.niki914.uikit.infra.component.SettingsListPageContent
import com.niki914.uikit.infra.nav.pageViewModel
import com.niki914.zafiro.api.McpServiceStatus
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.chat.helper.honestCopyToClipboard
import com.niki914.zafiro.app.ui.settings.form.SettingControlledExpandableTextItem
import com.niki914.zafiro.app.ui.settings.form.rememberEditableDetailFieldController
import com.niki914.zafiro.app.ui.model.McpServiceSettingsIntent
import com.niki914.zafiro.app.ui.model.McpServiceSettingsViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class McpServiceEditableField {
    Port,
    Token,
}

@Composable
fun McpServiceSettingsContent(
    viewModel: McpServiceSettingsViewModel = pageViewModel(),
) {
    val uiState by viewModel.uiStateFlow.collectAsState()
    val fieldController = rememberEditableDetailFieldController<McpServiceEditableField>(
        requestedFocusField = null,
        onRequestedFocusHandled = {},
    )

    val portBringIntoViewRequester = remember { BringIntoViewRequester() }
    val tokenBringIntoViewRequester = remember { BringIntoViewRequester() }

    val clipboard = LocalClipboard.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        viewModel.sendIntent(McpServiceSettingsIntent.Load)
    }

    LaunchedEffect(fieldController.expandedField) {
        when (fieldController.expandedField) {
            McpServiceEditableField.Port -> {
                delay(150)
                portBringIntoViewRequester.bringIntoView()
            }

            McpServiceEditableField.Token -> {
                delay(150)
                tokenBringIntoViewRequester.bringIntoView()
            }

            null -> Unit
        }
    }

    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    LaunchedEffect(imeBottomPx) {
        if (imeBottomPx > 0) {
            when (fieldController.expandedField) {
                McpServiceEditableField.Port -> portBringIntoViewRequester.bringIntoView()
                McpServiceEditableField.Token -> tokenBringIntoViewRequester.bringIntoView()
                null -> Unit
            }
        }
    }

    SettingsListPageContent(
        modifier = Modifier
            .imePadding()
            .pointerInput(Unit) {
                detectTapGestures(onTap = {
                    fieldController.clearActiveField()
                })
            },
    ) {
        // Block 1: 服务开关与运行状态
        SettingsGroupCard(title = stringResource(R.string.mcp_service_section_service)) {
            SettingToggleItem(
                title = stringResource(R.string.mcp_service_enable_title),
                description = stringResource(R.string.mcp_service_enable_summary),
                checked = uiState.enabled,
                onCheckedChange = { checked ->
                    fieldController.clearActiveField()
                    viewModel.sendIntent(McpServiceSettingsIntent.ToggleServer(checked))
                },
            )
            SettingsItemDivider()
            val runningUrl = (uiState.status as? McpServiceStatus.Running)
                ?.let { "http://${it.host}:${it.port}/mcp" }
            val statusState = when (val s = uiState.status) {
                is McpServiceStatus.Running -> stringResource(R.string.mcp_service_status_running)
                McpServiceStatus.Starting -> stringResource(R.string.mcp_service_status_starting)
                McpServiceStatus.Stopped -> stringResource(R.string.mcp_service_status_stopped)
                is McpServiceStatus.Error -> stringResource(R.string.mcp_service_status_error)
            }
            // 与运行中的 URL 同位置：出错时换成原因，点整行复制完整的错误栈
            val statusSummary = when (val s = uiState.status) {
                is McpServiceStatus.Running -> runningUrl
                is McpServiceStatus.Error -> s.message
                else -> null
            }
            val statusCopyText = when (val s = uiState.status) {
                is McpServiceStatus.Running -> runningUrl
                is McpServiceStatus.Error -> s.stackTrace
                else -> null
            }
            SettingsListItem(
                title = stringResource(R.string.mcp_service_status_title),
                currentState = statusState,
                summary = statusSummary,
                onClick = statusCopyText?.let { text ->
                    {
                        scope.launch {
                            honestCopyToClipboard(text = text, clipboard = clipboard, hapticFeedback = haptic)
                        }
                    }
                },
            )
            SettingsItemDivider()
            SettingToggleItem(
                title = stringResource(R.string.mcp_service_keep_screen_on_title),
                checked = uiState.keepScreenOn,
                onCheckedChange = { checked ->
                    fieldController.clearActiveField()
                    viewModel.sendIntent(McpServiceSettingsIntent.ToggleKeepScreenOn(checked))
                },
            )
        }

        // Block 2: 连接与鉴权
        SettingsGroupCard(title = stringResource(R.string.mcp_service_section_connection)) {
            Box(modifier = Modifier.bringIntoViewRequester(portBringIntoViewRequester)) {
                SettingControlledExpandableTextItem(
                    field = McpServiceEditableField.Port,
                    controller = fieldController,
                    title = stringResource(R.string.mcp_service_port_title),
                    value = uiState.portInput,
                    onValueChange = { newPort ->
                        viewModel.sendIntent(McpServiceSettingsIntent.PortChanged(newPort))
                    },
                    placeholder = stringResource(R.string.mcp_service_port_placeholder),
                    description = uiState.portError?.let { stringResource(R.string.mcp_service_port_error_invalid) },
                    enabled = true,
                    minLines = 1,
                    maxLines = 1,
                )
            }
            SettingsItemDivider()
            Box(modifier = Modifier.bringIntoViewRequester(tokenBringIntoViewRequester)) {
                SettingControlledExpandableTextItem(
                    field = McpServiceEditableField.Token,
                    controller = fieldController,
                    title = stringResource(R.string.mcp_service_token_title),
                    value = uiState.tokenInput,
                    onValueChange = { newToken ->
                        viewModel.sendIntent(McpServiceSettingsIntent.TokenChanged(newToken))
                    },
                    placeholder = stringResource(R.string.mcp_service_token_placeholder),
                    description = if (uiState.tokenInput.isBlank()) {
                        stringResource(R.string.mcp_service_token_description_empty)
                    } else {
                        stringResource(R.string.mcp_service_token_description_configured)
                    },
                    enabled = true,
                    minLines = 1,
                    maxLines = 1,
                    secretVisible = uiState.tokenVisible,
                    onToggleSecretVisibility = {
                        viewModel.sendIntent(McpServiceSettingsIntent.ToggleTokenVisibility)
                    },
                    toggleSecretVisibleContentDescription = stringResource(R.string.mcp_service_token_show),
                    toggleSecretHiddenContentDescription = stringResource(R.string.mcp_service_token_hide),
                )
            }
        }

        // Block 3: 工具集合
        SettingsGroupCard(title = stringResource(R.string.mcp_service_section_tools)) {
            uiState.tools.forEachIndexed { index, tool ->
                if (index > 0) {
                    SettingsItemDivider()
                }
                SettingToggleItem(
                    title = tool.displayName,
                    description = tool.description,
                    checked = tool.name in uiState.exposedTools,
                    onCheckedChange = { checked ->
                        fieldController.clearActiveField()
                        viewModel.sendIntent(
                            McpServiceSettingsIntent.ToggleTool(
                                tool.name,
                                checked
                            )
                        )
                    },
                )
            }
        }
    }
}
