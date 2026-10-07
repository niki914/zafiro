package com.niki914.zafiro.app.ui.content

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.niki914.uikit.infra.ConfirmationLiquidDialog
import com.niki914.uikit.infra.LiquidDialog
import com.niki914.uikit.infra.component.MaterialTintLiquidButton
import com.niki914.uikit.infra.component.SettingToggleItem
import com.niki914.uikit.infra.component.SettingsGroupCard
import com.niki914.uikit.infra.component.SettingsItemDivider
import com.niki914.uikit.infra.nav.pageViewModel
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.model.CustomPyToolDeleteConfirmationState
import com.niki914.zafiro.app.ui.model.CustomPyToolInlineError
import com.niki914.zafiro.app.ui.model.CustomPyToolSaveProgress
import com.niki914.zafiro.app.ui.model.CustomPyToolSettingsEffect
import com.niki914.zafiro.app.ui.model.CustomPyToolSettingsIntent
import com.niki914.zafiro.app.ui.model.CustomPyToolSettingsUiState
import com.niki914.zafiro.app.ui.model.CustomPyToolSettingsViewModel
import com.niki914.zafiro.app.ui.model.hasUnsavedChanges
import com.niki914.zafiro.app.ui.nav.CustomPyToolDetailPage

@Composable
fun CustomPyToolDetailContent(
    page: CustomPyToolDetailPage,
    onBack: () -> Unit,
) {
    val viewModel = pageViewModel<CustomPyToolSettingsViewModel>()
    val uiState by viewModel.uiStateFlow.collectAsState()
    var requestedFocusField by rememberSaveable {
        mutableStateOf<CustomPyToolEditableField?>(null)
    }

    EditableSettingsDetailChrome(
        isCreating = page.isCreating,
        hasUnsavedChanges = {
            uiState.formState.hasUnsavedChanges
        },
        onDelete = {
            viewModel.sendIntent(CustomPyToolSettingsIntent.RequestDelete)
        },
        onDiscardChanges = onBack,
        hasDeleteConfirmation = {
            uiState.deleteConfirmation != null
        },
        onDismissDeleteConfirmation = {
            viewModel.sendIntent(CustomPyToolSettingsIntent.DismissDeleteConfirmation)
        },
    ) {
        CustomPyToolDetailContentBody(
            uiState = uiState,
            requestedFocusField = requestedFocusField,
            onRequestedFocusHandled = {
                requestedFocusField = null
            },
            onNameChange = { value ->
                viewModel.sendIntent(CustomPyToolSettingsIntent.NameChanged(value))
            },
            onCodeChange = { value ->
                viewModel.sendIntent(CustomPyToolSettingsIntent.CodeChanged(value))
            },
            onEnabledChange = { value ->
                viewModel.sendIntent(CustomPyToolSettingsIntent.EnabledChanged(value))
            },
            onSave = {
                viewModel.sendIntent(CustomPyToolSettingsIntent.Save)
            },
        )

        CustomPyToolDeleteConfirmationDialog(
            state = uiState.deleteConfirmation,
            onDismissRequest = {
                viewModel.sendIntent(CustomPyToolSettingsIntent.DismissDeleteConfirmation)
            },
            onConfirmClick = {
                viewModel.sendIntent(CustomPyToolSettingsIntent.ConfirmDelete)
            },
        )

        CustomPyToolSaveProgressDialog(
            progress = uiState.saveProgress,
            onCancel = {
                viewModel.sendIntent(CustomPyToolSettingsIntent.CancelSave)
            },
            onConfirmSucceeded = {
                viewModel.sendIntent(CustomPyToolSettingsIntent.ConfirmSaveSucceeded)
            },
            onDismissFailed = {
                viewModel.sendIntent(CustomPyToolSettingsIntent.DismissSaveFailed)
            },
        )
    }

    LaunchedEffect(page.routeKey) {
        if (page.isCreating) {
            viewModel.sendIntent(CustomPyToolSettingsIntent.StartCreate)
        } else {
            viewModel.sendIntent(CustomPyToolSettingsIntent.Load)
        }
    }

    LaunchedEffect(page.routeKey, uiState.items.size, page.isCreating) {
        if (!page.isCreating && page.toolIndex in uiState.items.indices) {
            viewModel.sendIntent(CustomPyToolSettingsIntent.StartEdit(page.toolIndex))
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                CustomPyToolSettingsEffect.ExitDetail -> onBack()
                CustomPyToolSettingsEffect.FocusName -> {
                    requestedFocusField = CustomPyToolEditableField.Name
                }

                CustomPyToolSettingsEffect.FocusCode -> {
                    requestedFocusField = CustomPyToolEditableField.Code
                }
            }
        }
    }
}

@Composable
private fun CustomPyToolDetailContentBody(
    uiState: CustomPyToolSettingsUiState,
    requestedFocusField: CustomPyToolEditableField?,
    onRequestedFocusHandled: () -> Unit,
    onNameChange: (String) -> Unit,
    onCodeChange: (String) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onSave: () -> Unit,
) {
    EditableSettingsDetailFormScaffold(
        actionText = stringResource(R.string.custom_py_tool_save_action),
        requestedFocusField = requestedFocusField,
        onRequestedFocusHandled = onRequestedFocusHandled,
        onActionClick = onSave,
        description = stringResource(R.string.custom_py_tool_editor_description),
        inlineErrorText = customPyToolInlineErrorText(uiState.inlineError),
        actionEnabled = !uiState.isSaving,
    ) { fieldController ->
        CustomPyToolIdentitySettingsBlock(
            uiState = uiState,
            fieldController = fieldController,
            onNameChange = onNameChange,
            onEnabledChange = {
                fieldController.clearActiveField()
                onEnabledChange(it)
            },
        )

        CustomPyToolCodeSettingsBlock(
            uiState = uiState,
            fieldController = fieldController,
            onCodeChange = onCodeChange,
        )
    }
}

private enum class CustomPyToolEditableField {
    Name,
    Code,
}

@Composable
private fun CustomPyToolIdentitySettingsBlock(
    uiState: CustomPyToolSettingsUiState,
    fieldController: EditableDetailFieldController<CustomPyToolEditableField>,
    onNameChange: (String) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
) {
    SettingsGroupCard {
        SettingControlledExpandableTextItem(
            field = CustomPyToolEditableField.Name,
            controller = fieldController,
            title = stringResource(R.string.custom_py_tool_field_name),
            value = uiState.formState.name,
            onValueChange = onNameChange,
            placeholder = stringResource(R.string.custom_py_tool_field_name_hint),
            description = customPyToolFieldErrorText(uiState.formState.nameErrorResId),
            enabled = !uiState.isSaving,
            minLines = 1,
            maxLines = 1,
        )
        SettingsItemDivider()
        SettingToggleItem(
            title = stringResource(R.string.custom_py_tool_field_enabled),
            checked = uiState.formState.enabled,
            enabled = !uiState.isSaving,
            onCheckedChange = onEnabledChange,
        )
    }
}

@Composable
private fun CustomPyToolCodeSettingsBlock(
    uiState: CustomPyToolSettingsUiState,
    fieldController: EditableDetailFieldController<CustomPyToolEditableField>,
    onCodeChange: (String) -> Unit,
) {
    SettingsGroupCard {
        SettingControlledExpandableTextItem(
            field = CustomPyToolEditableField.Code,
            controller = fieldController,
            title = stringResource(R.string.custom_py_tool_field_code),
            value = uiState.formState.code,
            onValueChange = onCodeChange,
            placeholder = stringResource(R.string.custom_py_tool_field_code_hint),
            description = uiState.formState.codeErrorMessage
                ?: customPyToolFieldErrorText(uiState.formState.codeErrorResId),
            enabled = !uiState.isSaving,
            minLines = 6,
            maxLines = 16,
        )
    }
}

@Composable
private fun customPyToolFieldErrorText(errorResId: Int?): String? {
    return errorResId?.let { stringResource(id = it) }
}

@Composable
private fun customPyToolInlineErrorText(error: CustomPyToolInlineError?): String? {
    return when (error) {
        null -> null
        is CustomPyToolInlineError.LoadFailed -> stringResource(
            R.string.custom_py_tool_error_load_failed,
            error.message ?: stringResource(error.fallbackResId),
        )

        is CustomPyToolInlineError.SaveFailed -> stringResource(
            R.string.custom_py_tool_error_save_failed,
            error.message ?: stringResource(error.fallbackResId),
        )

        is CustomPyToolInlineError.DeleteFailed -> stringResource(
            R.string.custom_py_tool_error_delete_failed,
            error.message ?: stringResource(error.fallbackResId),
        )
    }
}

@Composable
private fun CustomPyToolDeleteConfirmationDialog(
    state: CustomPyToolDeleteConfirmationState?,
    onDismissRequest: () -> Unit,
    onConfirmClick: () -> Unit,
) {
    ConfirmationLiquidDialog(
        visible = state != null,
        onDismissRequest = onDismissRequest,
        title = stringResource(R.string.custom_py_tool_delete_dialog_title),
        text = stringResource(R.string.custom_py_tool_delete_dialog_text, state?.value.orEmpty()),
        negativeButtonText = stringResource(R.string.delete_dialog_cancel),
        positiveButtonText = stringResource(R.string.delete_dialog_confirm),
        onNegativeClick = onDismissRequest,
        onPositiveClick = onConfirmClick,
    )
}

/** 保存弹窗的文案与按钮配色：三态各一行，避免在 Composable 里堆 when 分支。 */
private data class CustomPyToolSaveDialogSpec(
    val title: String,
    val body: String,
    val action: String,
    val actionPrimary: Boolean,
    val checking: Boolean = false,
    val message: String? = null,
)

/**
 * 保存进度弹窗：同一个框展示「校验中 → 成功 / 失败」。
 *
 * 流程只由 state（[CustomPyToolSaveProgress]）驱动，不用 effect：反射是秒级操作，
 * 旋转屏幕或重组都不能丢状态。
 *
 * 每个状态只有一个按钮，系统返回键等同那个按钮：
 * 校验中 = 取消（真取消，不写盘）、成功 = 完成（返回列表）、失败 = 关闭（留在原页）。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CustomPyToolSaveProgressDialog(
    progress: CustomPyToolSaveProgress?,
    onCancel: () -> Unit,
    onConfirmSucceeded: () -> Unit,
    onDismissFailed: () -> Unit,
) {
    // 退场动画期间 progress 已置空，用最后一次的值渲染，避免标题与正文闪空
    var retained by remember { mutableStateOf<CustomPyToolSaveProgress?>(null) }
    LaunchedEffect(progress) {
        if (progress != null) retained = progress
    }
    val shown = progress ?: retained
    if (shown == null) return
    val spec = customPyToolSaveDialogSpec(shown)

    val onAction = {
        when (shown) {
            CustomPyToolSaveProgress.Checking -> onCancel()
            CustomPyToolSaveProgress.Succeeded -> onConfirmSucceeded()
            is CustomPyToolSaveProgress.CodeRejected -> onDismissFailed()
            is CustomPyToolSaveProgress.InternalError -> onDismissFailed()
        }
    }

    LiquidDialog(
        visible = progress != null,
        onDismissRequest = onAction,
        dismissOnBackgroundTap = false,
        title = {
            Text(
                text = spec.title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        content = {
            if (spec.checking) {
                LoadingIndicator(
                    modifier = Modifier.size(28.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = spec.body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
            val message = spec.message
            if (!message.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .padding(10.dp),
                ) {
                    // ToolResultText 自带高度上限、溢出才滚动、内容可长按选中复制，
                    // 报错原文直接用这个共享组件，不另起一套选择逻辑
                    ToolResultText(
                        text = message,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        },
        actions = {
            MaterialTintLiquidButton(
                text = spec.action,
                onClick = onAction,
                modifier = Modifier.weight(1f),
                containerColor = if (spec.actionPrimary) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
                contentColor = if (spec.actionPrimary) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        },
    )
}

@Composable
private fun customPyToolSaveDialogSpec(
    progress: CustomPyToolSaveProgress,
): CustomPyToolSaveDialogSpec {
    return when (progress) {
        CustomPyToolSaveProgress.Checking -> CustomPyToolSaveDialogSpec(
            title = stringResource(R.string.custom_py_tool_save_dialog_checking_title),
            body = stringResource(R.string.custom_py_tool_save_dialog_checking_text),
            action = stringResource(R.string.custom_py_tool_save_dialog_action_cancel),
            actionPrimary = false,
            checking = true,
        )

        CustomPyToolSaveProgress.Succeeded -> CustomPyToolSaveDialogSpec(
            title = stringResource(R.string.custom_py_tool_save_dialog_success_title),
            body = stringResource(R.string.custom_py_tool_save_dialog_success_text),
            action = stringResource(R.string.custom_py_tool_save_dialog_action_done),
            actionPrimary = true,
        )

        is CustomPyToolSaveProgress.CodeRejected -> CustomPyToolSaveDialogSpec(
            title = stringResource(R.string.custom_py_tool_save_dialog_code_failed_title),
            body = stringResource(R.string.custom_py_tool_save_dialog_code_failed_text),
            action = stringResource(R.string.custom_py_tool_save_dialog_action_close),
            actionPrimary = false,
            message = progress.message,
        )

        is CustomPyToolSaveProgress.InternalError -> CustomPyToolSaveDialogSpec(
            title = stringResource(R.string.custom_py_tool_save_dialog_internal_failed_title),
            body = stringResource(R.string.custom_py_tool_save_dialog_internal_failed_text),
            action = stringResource(R.string.custom_py_tool_save_dialog_action_close),
            actionPrimary = false,
            message = progress.message,
        )
    }
}
