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
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import com.niki914.uikit.infra.component.SettingToggleItem
import com.niki914.uikit.infra.component.SettingsGroupCard
import com.niki914.uikit.infra.component.SettingsItemDivider
import com.niki914.uikit.infra.component.SettingsListItem
import com.niki914.uikit.infra.component.SettingsListPageContent
import com.niki914.uikit.infra.nav.pageViewModel
import com.niki914.zafiro.api.McpHostStatus
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.content.SettingControlledExpandableTextItem
import com.niki914.zafiro.app.ui.content.rememberEditableDetailFieldController
import com.niki914.zafiro.app.ui.model.McpHostSettingsIntent
import com.niki914.zafiro.app.ui.model.McpHostSettingsViewModel
import kotlinx.coroutines.delay

private enum class McpHostEditableField {
    Port,
    Token,
}

@Composable
fun McpHostSettingsContent(
    viewModel: McpHostSettingsViewModel = pageViewModel(),
) {
    val uiState by viewModel.uiStateFlow.collectAsState()
    val fieldController = rememberEditableDetailFieldController<McpHostEditableField>(
        requestedFocusField = null,
        onRequestedFocusHandled = {},
    )

    val portBringIntoViewRequester = remember { BringIntoViewRequester() }
    val tokenBringIntoViewRequester = remember { BringIntoViewRequester() }

    LaunchedEffect(Unit) {
        viewModel.sendIntent(McpHostSettingsIntent.Load)
    }

    LaunchedEffect(fieldController.expandedField) {
        when (fieldController.expandedField) {
            McpHostEditableField.Port -> {
                delay(150)
                portBringIntoViewRequester.bringIntoView()
            }
            McpHostEditableField.Token -> {
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
                McpHostEditableField.Port -> portBringIntoViewRequester.bringIntoView()
                McpHostEditableField.Token -> tokenBringIntoViewRequester.bringIntoView()
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
        SettingsGroupCard(title = stringResource(R.string.mcp_host_section_service)) {
            SettingToggleItem(
                title = stringResource(R.string.mcp_host_enable_title),
                description = stringResource(R.string.mcp_host_enable_summary),
                checked = uiState.enabled,
                onCheckedChange = { checked ->
                    fieldController.clearActiveField()
                    viewModel.sendIntent(McpHostSettingsIntent.ToggleServer(checked))
                },
            )
            SettingsItemDivider()
            val statusState = when (val s = uiState.status) {
                is McpHostStatus.Running -> stringResource(R.string.mcp_host_status_running)
                McpHostStatus.Starting -> stringResource(R.string.mcp_host_status_starting)
                McpHostStatus.Stopped -> stringResource(R.string.mcp_host_status_stopped)
                is McpHostStatus.Error -> stringResource(R.string.mcp_host_status_error, s.message)
            }
            val statusSummary = when (val s = uiState.status) {
                is McpHostStatus.Running -> "http://${s.host}:${s.port}/mcp"
                else -> null
            }
            SettingsListItem(
                title = stringResource(R.string.mcp_host_status_title),
                currentState = statusState,
                summary = statusSummary,
                enabled = false,
            )
        }

        // Block 2: 连接与鉴权
        SettingsGroupCard(title = stringResource(R.string.mcp_host_section_connection)) {
            Box(modifier = Modifier.bringIntoViewRequester(portBringIntoViewRequester)) {
                SettingControlledExpandableTextItem(
                    field = McpHostEditableField.Port,
                    controller = fieldController,
                    title = stringResource(R.string.mcp_host_port_title),
                    value = uiState.portInput,
                    onValueChange = { newPort ->
                        viewModel.sendIntent(McpHostSettingsIntent.PortChanged(newPort))
                    },
                    placeholder = stringResource(R.string.mcp_host_port_placeholder),
                    description = uiState.portError?.let { stringResource(R.string.mcp_host_port_error_invalid) },
                    enabled = true,
                    minLines = 1,
                    maxLines = 1,
                )
            }
            SettingsItemDivider()
            Box(modifier = Modifier.bringIntoViewRequester(tokenBringIntoViewRequester)) {
                SettingControlledExpandableTextItem(
                    field = McpHostEditableField.Token,
                    controller = fieldController,
                    title = stringResource(R.string.mcp_host_token_title),
                    value = uiState.tokenInput,
                    onValueChange = { newToken ->
                        viewModel.sendIntent(McpHostSettingsIntent.TokenChanged(newToken))
                    },
                    placeholder = stringResource(R.string.mcp_host_token_placeholder),
                    description = if (uiState.tokenInput.isBlank()) {
                        stringResource(R.string.mcp_host_token_description_empty)
                    } else {
                        stringResource(R.string.mcp_host_token_description_configured)
                    },
                    enabled = true,
                    minLines = 1,
                    maxLines = 1,
                    secretVisible = uiState.tokenVisible,
                    onToggleSecretVisibility = {
                        viewModel.sendIntent(McpHostSettingsIntent.ToggleTokenVisibility)
                    },
                    toggleSecretVisibleContentDescription = stringResource(R.string.mcp_host_token_show),
                    toggleSecretHiddenContentDescription = stringResource(R.string.mcp_host_token_hide),
                )
            }
        }

        // Block 3: 工具集合
        SettingsGroupCard(title = stringResource(R.string.mcp_host_section_tools)) {
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
                        viewModel.sendIntent(McpHostSettingsIntent.ToggleTool(tool.name, checked))
                    },
                )
            }
        }
    }
}
