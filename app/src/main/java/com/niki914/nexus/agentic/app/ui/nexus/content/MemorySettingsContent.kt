package com.niki914.nexus.agentic.app.ui.nexus.content

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.niki914.nexus.agentic.app.R
import com.niki914.nexus.agentic.app.ui.infra.ConfirmationLiquidDialog
import com.niki914.nexus.agentic.app.ui.infra.LiquidDialog
import com.niki914.nexus.agentic.app.ui.infra.ProvideLiquidScreenContentForPreview
import com.niki914.nexus.agentic.app.ui.infra.component.LiquidTextField
import com.niki914.nexus.agentic.app.ui.infra.component.MaterialTintLiquidButton
import com.niki914.nexus.agentic.app.ui.infra.component.SettingsGroupCard
import com.niki914.nexus.agentic.app.ui.infra.component.SettingsListPageContent
import com.niki914.nexus.agentic.app.ui.infra.component.SwipeDismissSettingsItemCard
import com.niki914.nexus.agentic.app.ui.infra.nav.pageViewModel
import com.niki914.nexus.agentic.app.ui.nexus.PageBackHandler
import com.niki914.nexus.agentic.app.ui.nexus.PageChromeContribution
import com.niki914.nexus.agentic.app.ui.nexus.RegisterPageChrome
import com.niki914.nexus.agentic.app.ui.nexus.model.MemoryEditDialogState
import com.niki914.nexus.agentic.app.ui.nexus.model.MemoryDeleteConfirmationState
import com.niki914.nexus.agentic.app.ui.nexus.model.MemoryInlineError
import com.niki914.nexus.agentic.app.ui.nexus.model.MemorySettingsIntent
import com.niki914.nexus.agentic.app.ui.nexus.model.MemorySettingsUiState
import com.niki914.nexus.agentic.app.ui.nexus.model.MemorySettingsViewModel
import com.niki914.nexus.agentic.app.ui.nexus.nav.TopBarActionSpec
import kotlinx.coroutines.delay

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

    MemoryEditDialog(
        state = uiState.editingDialog,
        isSaving = uiState.isSaving,
        onValueChange = onDialogValueChange,
        onDismissRequest = onDialogDismiss,
        onSaveClick = onDialogSave,
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
private fun MemoryEditDialog(
    state: MemoryEditDialogState?,
    isSaving: Boolean,
    onValueChange: (String) -> Unit,
    onDismissRequest: () -> Unit,
    onSaveClick: () -> Unit,
) {
    var retainedState by remember { mutableStateOf<MemoryEditDialogState?>(null) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(state) {
        if (state != null) {
            retainedState = state
            delay(100)
            focusRequester.requestFocus()
        }
    }
    val dialogState = state ?: retainedState
    LiquidDialog(
        visible = state != null,
        onDismissRequest = onDismissRequest,
        title = {
            Text(
                text = stringResource(
                    if (dialogState?.index == null) {
                        R.string.memory_editor_title_create
                    } else {
                        R.string.memory_editor_title_edit
                    }
                ),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Start,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        content = {
            Box(modifier = Modifier.padding(horizontal = 2.dp)) {
                Text(
                    text = stringResource(R.string.memory_field_content),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            LiquidTextField(
                value = dialogState?.value.orEmpty(),
                onValueChange = onValueChange,
                placeholder = stringResource(R.string.memory_field_content_hint),
                enabled = !isSaving,
                singleLine = true,
                minLines = 1,
                maxLines = 1,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
        },
        actions = {
            MaterialTintLiquidButton(
                text = stringResource(R.string.memory_save_action),
                enabled = !isSaving,
                onClick = onSaveClick,
                modifier = Modifier.weight(1f),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
            MaterialTintLiquidButton(
                text = stringResource(R.string.memory_cancel_action),
                enabled = !isSaving,
                onClick = onDismissRequest,
                modifier = Modifier.weight(1f),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurface,
            )
        },
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
            MemoryEditDialog(
                state = MemoryEditDialogState(
                    index = 0,
                    value = "回答要简洁、直接、偏工程化。",
                ),
                isSaving = false,
                onValueChange = {},
                onDismissRequest = {},
                onSaveClick = {},
            )
        }
    }
}
