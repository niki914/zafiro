package com.niki914.zafiro.app.ui.content.dialog

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.niki914.uikit.dialog.ZafiroDialog
import com.niki914.uikit.dialog.ZafiroDialogButton
import com.niki914.zafiro.app.R

/**
 * 屏幕控制知情同意对话框（无障碍 + 悬浮窗）。
 */
@Composable
internal fun ScreenControlConsentDialog(
    onAgree: () -> Unit,
    onDeny: () -> Unit,
) {
    ZafiroDialog(
        visible = true,
        onDismissRequest = onDeny,
        dismissOnBackgroundTap = false,
        title = {
            Text(
                text = stringResource(R.string.screen_control_consent_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        text = {
            Text(
                text = stringResource(R.string.screen_control_consent_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        actions = {
            ZafiroDialogButton(
                text = stringResource(R.string.screen_control_consent_deny),
                onClick = onDeny,
                isPrimary = false,
            )
            ZafiroDialogButton(
                text = stringResource(R.string.screen_control_consent_agree),
                onClick = onAgree,
                isPrimary = true,
            )
        },
    )
}
