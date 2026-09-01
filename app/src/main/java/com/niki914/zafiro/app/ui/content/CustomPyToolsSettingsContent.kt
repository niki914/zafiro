package com.niki914.zafiro.app.ui.content

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
import com.niki914.uikit.infra.component.settings.SettingsPageSpec
import com.niki914.uikit.infra.component.settings.SettingsRowAction
import com.niki914.uikit.infra.component.settings.SettingsRowSpec
import com.niki914.uikit.infra.component.settings.SettingsSectionLayout
import com.niki914.uikit.infra.component.settings.SettingsSectionSpec
import com.niki914.uikit.infra.component.settings.SettingsSpecPageContent
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.PageChromeContribution
import com.niki914.zafiro.app.ui.RegisterPageChrome
import com.niki914.zafiro.app.ui.model.CustomPyToolItem
import com.niki914.zafiro.app.ui.nav.TopBarActionSpec
import com.niki914.zafiro.repo.XRepo
import kotlinx.coroutines.launch

private const val CUSTOM_PY_TOOL_ROW_ID_PREFIX = "custom.py.tool."

@Composable
fun CustomPyToolsSettingsContent(
    onOpenToolDetail: (toolName: String, toolIndex: Int, isCreating: Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()

    var items by remember { mutableStateOf<List<CustomPyToolItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isSaving by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    val saveFailedTemplate = stringResource(R.string.custom_py_tool_save_failed)
    val createTitle = stringResource(R.string.custom_py_tool_editor_title_create)
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
        items = XRepo.customPyTools.list()
            .map { CustomPyToolItem(name = it.name, enabled = it.enabled) }
        isLoading = false
    }
    val pageDescription = when {
        isLoading || items.isNotEmpty() -> stringResource(R.string.custom_py_tool_page_description)
        else -> stringResource(R.string.custom_py_tool_page_empty_description)
    }
    val loadingText = stringResource(R.string.custom_py_tool_loading)

    SettingsSpecPageContent(
        spec = customPyToolsSettingsSpec(
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
                        customPyToolIndexFromRowId(action.id) ?: return@SettingsSpecPageContent
                    val item = items.getOrNull(index) ?: return@SettingsSpecPageContent
                    onOpenToolDetail(item.name, index, false)
                }

                is SettingsRowAction.ToggleChanged -> {
                    val index =
                        customPyToolIndexFromRowId(action.id) ?: return@SettingsSpecPageContent
                    val item = items.getOrNull(index) ?: return@SettingsSpecPageContent
                    val updatedItems = items.toMutableList().also { mutableItems ->
                        mutableItems[index] = item.copy(enabled = action.checked)
                    }
                    scope.launch {
                        isSaving = true
                        runCatching {
                            XRepo.customPyTools.setEnabled(item.name, action.checked)
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

private fun customPyToolsSettingsSpec(
    items: List<CustomPyToolItem>,
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
                        id = customPyToolRowId(index),
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

private fun customPyToolRowId(index: Int): String = "$CUSTOM_PY_TOOL_ROW_ID_PREFIX$index"

private fun customPyToolIndexFromRowId(id: String): Int? {
    if (!id.startsWith(CUSTOM_PY_TOOL_ROW_ID_PREFIX)) return null
    return id.removePrefix(CUSTOM_PY_TOOL_ROW_ID_PREFIX).toIntOrNull()
}
