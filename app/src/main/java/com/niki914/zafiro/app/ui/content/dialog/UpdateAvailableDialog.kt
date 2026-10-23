package com.niki914.zafiro.app.ui.content.dialog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.niki914.uikit.dialog.ZafiroDialog
import com.niki914.uikit.dialog.ZafiroDialogButton
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.chat.composable.text.AssistantOutputText
import com.niki914.zafiro.repo.UpdateCheckResult

/**
 * 更新弹窗：标题 + 版本提示 + GitHub release 原文（markdown，可滚动）。
 * 只负责展示；"同一版本只弹一次" 的记账在 [com.niki914.zafiro.business.application.UpdateCheckHolder.dismiss]。
 */
@Composable
internal fun UpdateAvailableDialog(
    update: UpdateCheckResult?,
    onDismiss: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    ZafiroDialog(
        visible = update?.hasUpdate == true,
        onDismissRequest = onDismiss,
        dismissOnBackgroundTap = false,
        title = {
            Text(
                text = stringResource(R.string.update_dialog_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        text = {
            Text(
                text = stringResource(
                    R.string.update_dialog_text,
                    update?.remoteVersion.orEmpty(),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        content = {
            val notes = update?.releaseNotes.orEmpty()
            if (notes.isNotBlank()) {
                // 说明单独限高滚动，标题与按钮常驻
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    AssistantOutputText(notes)
                }
            }
        },
        actions = {
            ZafiroDialogButton(
                text = stringResource(R.string.update_dialog_cancel),
                onClick = onDismiss,
                isPrimary = false,
            )
            ZafiroDialogButton(
                text = stringResource(R.string.update_dialog_confirm),
                onClick = {
                    update?.releaseUrl?.takeIf { it.isNotBlank() }
                        ?.let(uriHandler::openUri)
                    onDismiss()
                },
                isPrimary = true,
            )
        },
    )
}
