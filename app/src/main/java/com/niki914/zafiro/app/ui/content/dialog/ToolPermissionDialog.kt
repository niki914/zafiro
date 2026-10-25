package com.niki914.zafiro.app.ui.content.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.niki914.uikit.dialog.ZafiroDialog
import com.niki914.uikit.dialog.ZafiroDialogButton
import com.niki914.zafiro.app.R
import com.niki914.zafiro.api.model.ApprovalRequest

/**
 * CONFIRM 型执行规则的用户确认对话框（永不超时，PRD §3）。
 */
@Composable
internal fun ToolPermissionDialog(
    request: ApprovalRequest.ToolExecution,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
) {
    ZafiroDialog(
        visible = true,
        onDismissRequest = onDeny,
        dismissOnBackgroundTap = false,
        title = {
            Text(
                text = stringResource(R.string.tool_permission_dialog_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.tool_permission_request_intro, request.toolName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = request.command,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 10,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .padding(10.dp),
                )
                Text(
                    text = stringResource(
                        R.string.tool_permission_matched_rule,
                        request.ruleName,
                        request.matchedPattern,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        actions = {
            ZafiroDialogButton(
                text = stringResource(R.string.tool_permission_deny),
                onClick = onDeny,
                isPrimary = false,
            )
            ZafiroDialogButton(
                text = stringResource(R.string.tool_permission_allow),
                onClick = onAllow,
                isPrimary = true,
            )
        },
    )
}
