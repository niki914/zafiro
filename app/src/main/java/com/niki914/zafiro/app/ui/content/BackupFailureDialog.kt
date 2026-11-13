package com.niki914.zafiro.app.ui.content

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.niki914.uikit.dialog.ZafiroDialog
import com.niki914.uikit.dialog.ZafiroDialogButton
import com.niki914.uikit.infra.shape.G2FieldShape
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.chat.helper.honestCopyToClipboard
import com.niki914.zafiro.app.ui.model.backup.BackupFailureState
import com.niki914.zafiro.app.ui.model.backup.BackupSheetKind
import kotlinx.coroutines.launch

@Composable
fun BackupFailureDialog(
    failure: BackupFailureState,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboard.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val title = stringResource(
        when (failure.kind) {
            BackupSheetKind.Import -> R.string.ui_backup_import_failed
            BackupSheetKind.Export -> R.string.ui_backup_export_failed
        },
    )
    val unchanged = if (failure.dataUnmodified) {
        stringResource(R.string.ui_backup_data_unchanged)
    } else {
        null
    }
    val copied = buildString {
        appendLine(title)
        if (unchanged != null) appendLine(unchanged)
        appendLine()
        append(failure.stackTrace.trim())
    }

    ZafiroDialog(
        visible = true,
        onDismissRequest = onDismiss,
        dismissOnBackgroundTap = false,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            ) {
                if (unchanged != null) {
                    Text(
                        text = unchanged,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .heightIn(max = 180.dp),
                    shape = G2FieldShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    SelectionContainer {
                        Text(
                            text = failure.stackTrace,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .verticalScroll(scrollState)
                                .padding(8.dp),
                        )
                    }
                }
            }
        },
        actions = {
            ZafiroDialogButton(
                text = stringResource(R.string.crash_dialog_close),
                onClick = onDismiss,
                isPrimary = false,
            )
            ZafiroDialogButton(
                text = stringResource(R.string.crash_dialog_copy),
                onClick = {
                    scope.launch {
                        honestCopyToClipboard(
                            text = copied,
                            clipboard = clipboard,
                            hapticFeedback = haptic,
                            toastRes = R.string.ui_backup_failure_copied,
                        )
                    }
                },
                isPrimary = true,
            )
        },
    )
}
