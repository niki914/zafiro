package com.niki914.nexus.agentic.app.ui.nexus.content

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.niki914.nexus.agentic.app.R
import com.niki914.nexus.agentic.app.ui.infra.component.settings.SettingsPageSpec
import com.niki914.nexus.agentic.app.ui.infra.component.settings.SettingsRowAction
import com.niki914.nexus.agentic.app.ui.infra.component.settings.SettingsRowSpec
import com.niki914.nexus.agentic.app.ui.infra.component.settings.SettingsSectionLayout
import com.niki914.nexus.agentic.app.ui.infra.component.settings.SettingsSectionSpec
import com.niki914.nexus.agentic.app.ui.infra.component.settings.SettingsSpecPageContent
import com.niki914.nexus.agentic.app.ui.nexus.PageChromeContribution
import com.niki914.nexus.agentic.app.ui.nexus.RegisterPageChrome
import com.niki914.nexus.agentic.app.ui.nexus.nav.TopBarActionSpec
import com.niki914.nexus.agentic.repo.XRepo
import kotlinx.coroutines.launch
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeCustomTool as CustomTool

private const val CUSTOM_TOOL_ROW_ID_PREFIX = "custom.tool."

@Composable
fun CustomShellToolsSettingsContent(
    onOpenToolDetail: (toolName: String, toolIndex: Int, isCreating: Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()

    var items by remember { mutableStateOf<List<CustomToolItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isSaving by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    val saveFailedTemplate = stringResource(R.string.custom_tool_save_failed)
    val createTitle = stringResource(R.string.custom_tool_editor_title_create)
    val latestOnOpenToolDetail by rememberUpdatedState(onOpenToolDetail)
    val pageChromeContribution = remember(createTitle) {
        PageChromeContribution(
            rightAction = TopBarActionSpec(
                icon = Icons.Default.Add,
                onClick = {
                    latestOnOpenToolDetail(createTitle, -1, true)
                },
                contentDescription = createTitle,
            ),
        )
    }
    RegisterPageChrome(pageChromeContribution)

    LaunchedEffect(Unit) {
        items = XRepo.customTools.list().map { it.toItem() }
        isLoading = false
    }
    val pageDescription = when {
        isLoading || items.isNotEmpty() -> stringResource(R.string.custom_tool_page_description)
        else -> stringResource(R.string.custom_tool_page_empty_description)
    }
    val loadingText = stringResource(R.string.custom_tool_loading)

    SettingsSpecPageContent(
        spec = customToolsSettingsSpec(
            items = items,
            isLoading = isLoading,
            isSaving = isSaving,
            pageDescription = pageDescription,
            loadingText = loadingText,
        ),
        contentAfterSections = {
            statusMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        onAction = { action ->
            when (action) {
                is SettingsRowAction.Navigate -> {
                    val index =
                        customToolIndexFromRowId(action.id) ?: return@SettingsSpecPageContent
                    val item = items.getOrNull(index) ?: return@SettingsSpecPageContent
                    onOpenToolDetail(item.name, index, false)
                }

                is SettingsRowAction.ToggleChanged -> {
                    val index =
                        customToolIndexFromRowId(action.id) ?: return@SettingsSpecPageContent
                    val item = items.getOrNull(index) ?: return@SettingsSpecPageContent
                    val updatedItems = items.toMutableList().also { mutableItems ->
                        mutableItems[index] = item.copy(enabled = action.checked)
                    }
                    scope.launch {
                        isSaving = true
                        runCatching {
                            XRepo.customTools.setEnabled(item.name, action.checked)
                        }.onSuccess {
                            items = updatedItems
                            statusMessage = null
                        }.onFailure { throwable ->
                            statusMessage = saveFailedTemplate.format(
                                throwable.message ?: throwable::class.java.simpleName
                            )
                        }
                        isSaving = false
                    }
                }

                is SettingsRowAction.Click -> Unit
            }
        },
    )
}

private fun customToolsSettingsSpec(
    items: List<CustomToolItem>,
    isLoading: Boolean,
    isSaving: Boolean,
    pageDescription: String,
    loadingText: String,
): SettingsPageSpec {
    val sections = when {
        isLoading -> listOf(
            SettingsSectionSpec(
                layout = SettingsSectionLayout.GroupedCard,
                rows = listOf(
                    SettingsRowSpec.Message(
                        title = loadingText,
                        horizontalPadding = 0.dp,
                        verticalPadding = 0.dp,
                    )
                ),
            )
        )

        items.isNotEmpty() -> listOf(
            SettingsSectionSpec(
                layout = SettingsSectionLayout.CardList,
                rows = items.mapIndexed { index, item ->
                    SettingsRowSpec.ToggleNavigation(
                        id = customToolRowId(index),
                        title = item.name,
                        checked = item.enabled,
                        enabled = !isSaving,
                    )
                },
            )
        )

        else -> emptyList()
    }

    return SettingsPageSpec(
        description = pageDescription,
        sections = sections,
    )
}

private fun customToolRowId(index: Int): String = "$CUSTOM_TOOL_ROW_ID_PREFIX$index"

private fun customToolIndexFromRowId(id: String): Int? {
    if (!id.startsWith(CUSTOM_TOOL_ROW_ID_PREFIX)) return null
    return id.removePrefix(CUSTOM_TOOL_ROW_ID_PREFIX).toIntOrNull()
}

private data class CustomToolItem(
    val name: String,
    val description: String,
    val enabled: Boolean,
    val command: String,
)

private fun CustomTool.toItem(): CustomToolItem {
    return CustomToolItem(
        name = name,
        description = description,
        enabled = enabled,
        command = command,
    )
}
