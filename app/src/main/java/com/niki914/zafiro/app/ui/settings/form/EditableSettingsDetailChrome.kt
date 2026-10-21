package com.niki914.zafiro.app.ui.settings.form

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.niki914.uikit.infra.ConfirmationLiquidDialog
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.PageBackHandler
import com.niki914.zafiro.app.ui.PageChromeContribution
import com.niki914.zafiro.app.ui.RegisterPageChrome
import com.niki914.zafiro.app.ui.nav.TopBarActionSpec

/**
 * 设置详情页顶层交互 Chrome。
 *
 * 统一管理：
 * 1. 顶部操作栏动作（如右上角删除按钮）。
 * 2. 系统返回键与导航返回拦截。
 * 3. 未保存修改时的挽留弹窗（按需计算变更项，不侵入重组过程）。
 * 4. 删除确认弹窗生命周期协同。
 */
@Composable
fun EditableSettingsDetailChrome(
    isCreating: Boolean,
    hasUnsavedChanges: () -> Boolean,
    onDiscardChanges: () -> Unit,
    unsavedFieldLabelRes: (() -> List<Int>)? = null,
    onDelete: (() -> Unit)? = null,
    rightAction: TopBarActionSpec? = null,
    hasDeleteConfirmation: () -> Boolean = { false },
    onDismissDeleteConfirmation: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val latestHasUnsavedChanges by rememberUpdatedState(hasUnsavedChanges)
    val latestUnsavedFieldLabelRes by rememberUpdatedState(unsavedFieldLabelRes)
    val latestOnDelete by rememberUpdatedState(onDelete)
    val latestRightAction by rememberUpdatedState(rightAction)
    val latestOnDiscardChanges by rememberUpdatedState(onDiscardChanges)
    val latestHasDeleteConfirmation by rememberUpdatedState(hasDeleteConfirmation)
    val latestOnDismissDeleteConfirmation by rememberUpdatedState(onDismissDeleteConfirmation)
    var showUnsavedChangesDialog by rememberSaveable { mutableStateOf(false) }

    val pageChromeContribution = remember(
        isCreating,
        onDelete != null,
        rightAction?.icon,
        rightAction?.contentDescription,
    ) {
        PageChromeContribution(
            rightAction = rightAction?.copy(
                onClick = {
                    latestRightAction?.onClick?.invoke()
                },
            ) ?: if (isCreating || onDelete == null) {
                null
            } else {
                TopBarActionSpec(
                    icon = Icons.Default.Delete,
                    onClick = {
                        latestOnDelete?.invoke()
                    },
                    contentDescription = "Delete Button",
                )
            },
            backHandler = PageBackHandler(
                shouldConsumeBack = {
                    latestHasUnsavedChanges() || latestHasDeleteConfirmation()
                },
                onConsumeBack = {
                    if (latestHasDeleteConfirmation()) {
                        latestOnDismissDeleteConfirmation()
                    } else {
                        showUnsavedChangesDialog = true
                    }
                },
            ),
        )
    }
    RegisterPageChrome(pageChromeContribution)

    content()

    // 仅在弹窗可见时进行按需计算与展示，不参与平时的输入重组
    if (showUnsavedChangesDialog) {
        val changedLabelResList = latestUnsavedFieldLabelRes?.invoke().orEmpty()
        val dialogText = if (changedLabelResList.isNotEmpty()) {
            buildString {
                appendLine(stringResource(R.string.unsaved_changes_dialog_fields_prefix))
                changedLabelResList.forEach { resId ->
                    appendLine("• ${stringResource(resId)}")
                }
                append("\n")
                append(stringResource(R.string.unsaved_changes_dialog_text))
            }
        } else {
            stringResource(R.string.unsaved_changes_dialog_text)
        }

        ConfirmationLiquidDialog(
            visible = true,
            onDismissRequest = {
                showUnsavedChangesDialog = false
            },
            title = stringResource(R.string.unsaved_changes_dialog_title),
            text = dialogText,
            negativeButtonText = stringResource(R.string.unsaved_changes_dialog_cancel),
            positiveButtonText = stringResource(R.string.unsaved_changes_dialog_confirm_exit),
            onNegativeClick = {
                showUnsavedChangesDialog = false
            },
            onPositiveClick = {
                showUnsavedChangesDialog = false
                latestOnDiscardChanges()
            },
        )
    }
}

/**
 * 直接基于 [FormSpec] 表单规格的便利重载。
 * 自动绑定未保存判断及变更字段资源解析。
 */
@Composable
fun <State, Field> EditableSettingsDetailChrome(
    isCreating: Boolean,
    formSpec: FormSpec<State, Field>,
    baseline: State?,
    current: State,
    onDiscardChanges: () -> Unit,
    onDelete: (() -> Unit)? = null,
    rightAction: TopBarActionSpec? = null,
    hasDeleteConfirmation: () -> Boolean = { false },
    onDismissDeleteConfirmation: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    EditableSettingsDetailChrome(
        isCreating = isCreating,
        hasUnsavedChanges = { formSpec.hasChanges(baseline, current) },
        onDiscardChanges = onDiscardChanges,
        unsavedFieldLabelRes = { formSpec.dirtyFieldLabelRes(baseline, current) },
        onDelete = onDelete,
        rightAction = rightAction,
        hasDeleteConfirmation = hasDeleteConfirmation,
        onDismissDeleteConfirmation = onDismissDeleteConfirmation,
        content = content,
    )
}
