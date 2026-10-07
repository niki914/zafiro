package com.niki914.zafiro.app.ui.content

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.niki914.uikit.infra.ConfirmationLiquidDialog
import com.niki914.uikit.infra.ProvideLiquidScreenContentForPreview
import com.niki914.uikit.infra.component.SettingsGroupCard
import com.niki914.uikit.infra.component.SettingsListPageContent
import com.niki914.uikit.infra.component.SwipeDismissSettingsItemCard
import com.niki914.uikit.infra.nav.pageViewModel
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.PageBackHandler
import com.niki914.zafiro.app.ui.PageChromeContribution
import com.niki914.zafiro.app.ui.RegisterPageChrome
import com.niki914.zafiro.app.ui.model.MemoryDeleteConfirmationState
import com.niki914.zafiro.app.ui.model.MemoryInlineError
import com.niki914.zafiro.app.ui.model.MemorySettingsIntent
import com.niki914.zafiro.app.ui.model.MemorySettingsUiState
import com.niki914.zafiro.app.ui.model.MemorySettingsViewModel
import com.niki914.zafiro.app.ui.nav.TopBarActionSpec

@Composable
fun MemorySettingsContent() {
    val viewModel = pageViewModel<MemorySettingsViewModel>()
    val uiState by viewModel.uiStateFlow.collectAsState()
    val latestUiState by rememberUpdatedState(uiState)
    val latestViewModel by rememberUpdatedState(viewModel)
    val pageChromeContribution = remember(viewModel) {
        PageChromeContribution(
            rightAction = TopBarActionSpec(
                icon = Icons.Default.Add,
                onClick = {
                    viewModel.sendIntent(MemorySettingsIntent.StartCreate)
                },
            ),
            backHandler = PageBackHandler(
                shouldConsumeBack = {
                    latestUiState.editingDialog != null || latestUiState.deleteConfirmation != null
                },
                onConsumeBack = {
                    if (latestUiState.editingDialog != null) {
                        latestViewModel.sendIntent(MemorySettingsIntent.DismissEditDialog)
                    } else if (latestUiState.deleteConfirmation != null) {
                        latestViewModel.sendIntent(MemorySettingsIntent.DismissDeleteConfirmation)
                    }
                },
            ),
        )
    }
    RegisterPageChrome(pageChromeContribution)

    LaunchedEffect(Unit) {
        viewModel.sendIntent(MemorySettingsIntent.Load)
    }

    MemorySettingsContentBody(
        uiState = uiState,
        onStartEdit = { index ->
            viewModel.sendIntent(MemorySettingsIntent.StartEdit(index))
        },
        onRequestDelete = { index ->
            viewModel.sendIntent(MemorySettingsIntent.RequestDeleteItem(index))
        },
        onDialogValueChange = { value ->
            viewModel.sendIntent(MemorySettingsIntent.EditValueChanged(value))
        },
        onDialogDismiss = {
            viewModel.sendIntent(MemorySettingsIntent.DismissEditDialog)
        },
        onDialogSave = {
            viewModel.sendIntent(MemorySettingsIntent.SaveEditDialog)
        },
        onDeleteConfirmationDismiss = {
            viewModel.sendIntent(MemorySettingsIntent.DismissDeleteConfirmation)
        },
        onDeleteConfirmationConfirm = {
            viewModel.sendIntent(MemorySettingsIntent.ConfirmDeleteItem)
        },
    )
}

@Composable
private fun MemorySettingsContentBody(
    uiState: MemorySettingsUiState,
    onStartEdit: (Int) -> Unit,
    onRequestDelete: (Int) -> Unit,
    onDialogValueChange: (String) -> Unit,
    onDialogDismiss: () -> Unit,
    onDialogSave: () -> Unit,
    onDeleteConfirmationDismiss: () -> Unit,
    onDeleteConfirmationConfirm: () -> Unit,
) {
    val pageDescription = when {
        uiState.isLoading || uiState.items.isNotEmpty() -> {
            stringResource(R.string.memory_page_description)
        }

        else -> stringResource(R.string.memory_page_empty_description)
    }
    SettingsListPageContent(
        description = pageDescription,
    ) {
        if (uiState.isLoading) {
            SettingsGroupCard {
                MemoryListMessage(text = stringResource(R.string.memory_loading))
            }
        } else if (uiState.items.isNotEmpty()) {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                uiState.items.forEachIndexed { index, item ->
                    key(index to item) {
                        SwipeDismissSettingsItemCard(
                            title = item,
                            enabled = !uiState.isSaving,
                            onClick = {
                                onStartEdit(index)
                            },
                            onDismissRequest = {
                                onRequestDelete(index)
                            },
                        )
                    }
                }
            }
        }

        uiState.inlineError?.let { error ->
            MemoryInlineErrorText(error = error)
        }
    }

    InputLiquidDialog(
        visible = uiState.editingDialog != null,
        onDismissRequest = onDialogDismiss,
        title = stringResource(
            if (uiState.editingDialog?.index == null) {
                R.string.memory_editor_title_create
            } else {
                R.string.memory_editor_title_edit
            }
        ),
        hint = stringResource(R.string.memory_field_content),
        value = uiState.editingDialog?.value.orEmpty(),
        onValueChange = onDialogValueChange,
        placeholder = stringResource(R.string.memory_field_content_hint),
        positiveButtonText = stringResource(R.string.memory_save_action),
        positiveEnabled = !uiState.isSaving,
        enabled = !uiState.isSaving,
        onPositiveClick = onDialogSave,
    )

    MemoryDeleteConfirmationDialog(
        state = uiState.deleteConfirmation,
        onDismissRequest = onDeleteConfirmationDismiss,
        onConfirmClick = onDeleteConfirmationConfirm,
    )
}

@Composable
private fun MemoryDeleteConfirmationDialog(
    state: MemoryDeleteConfirmationState?,
    onDismissRequest: () -> Unit,
    onConfirmClick: () -> Unit,
) {
    val deleteValue = state?.value.orEmpty()
    ConfirmationLiquidDialog(
        visible = state != null,
        onDismissRequest = onDismissRequest,
        title = stringResource(R.string.memory_delete_dialog_title),
        text = stringResource(R.string.memory_delete_dialog_text, deleteValue),
        negativeButtonText = stringResource(R.string.memory_delete_dialog_cancel),
        positiveButtonText = stringResource(R.string.memory_delete_dialog_confirm),
        onNegativeClick = onDismissRequest,
        onPositiveClick = onConfirmClick,
    )
}

@Composable
private fun MemoryListMessage(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun MemoryInlineErrorText(error: MemoryInlineError) {
    val message = when (error) {
        is MemoryInlineError.LoadFailed -> stringResource(
            R.string.memory_error_load_failed,
            error.message ?: stringResource(error.fallbackResId),
        )

        is MemoryInlineError.SaveFailed -> stringResource(
            R.string.memory_error_save_failed,
            error.message ?: stringResource(error.fallbackResId),
        )

        is MemoryInlineError.DeleteFailed -> stringResource(
            R.string.memory_error_delete_failed,
            error.message ?: stringResource(error.fallbackResId),
        )
    }
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
}

@Preview(name = "Memory Edit Dialog", showBackground = true, widthDp = 420, heightDp = 900)
@Composable
private fun MemoryEditDialogPreview() {
    MaterialTheme {
        ProvideLiquidScreenContentForPreview(topPadding = 0.dp) {
            InputLiquidDialog(
                visible = true,
                onDismissRequest = {},
                title = stringResource(R.string.memory_editor_title_edit),
                hint = stringResource(R.string.memory_field_content),
                value = "回答要简洁、直接、偏工程化。",
                onValueChange = {},
                placeholder = stringResource(R.string.memory_field_content_hint),
                positiveButtonText = stringResource(R.string.memory_save_action),
                onPositiveClick = {},
            )
        }
    }
}
