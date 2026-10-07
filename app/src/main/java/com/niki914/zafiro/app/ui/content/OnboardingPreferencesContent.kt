package com.niki914.zafiro.app.ui.content

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.niki914.uikit.base.BaseTheme
import com.niki914.uikit.infra.ProvideLiquidScreenContentForPreview
import com.niki914.uikit.infra.component.SettingsDetailFormScaffold
import com.niki914.uikit.infra.component.SettingsGroupCard
import com.niki914.uikit.infra.component.SettingsItemDivider
import com.niki914.uikit.infra.component.SettingsListItem
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.model.OnboardingPreferencesIntent
import com.niki914.zafiro.app.ui.model.OnboardingPreferencesUiState

/**
 * onboarding 权限配置页：四项权限（点击授权）+ 安全规则（可增删）。
 * 权限行与文件权限同一形态：右侧只显示「已开启 / 点击授权」，点击即申请，不用开关。
 * 底部动作由 [SettingsDetailFormScaffold] 提供，固定在框架内、不随内容滚动。
 */
@Composable
fun OnboardingPreferencesContent(
    uiState: OnboardingPreferencesUiState,
    onIntent: (OnboardingPreferencesIntent) -> Unit,
) {
    SettingsDetailFormScaffold(
        actionText = stringResource(
            if (uiState.allGranted) {
                R.string.ui_onboard_done_enter_home
            } else {
                R.string.ui_onboard_preferences_action_grant_all
            }
        ),
        onActionClick = {
            if (uiState.allGranted) {
                onIntent(OnboardingPreferencesIntent.Complete)
            } else {
                onIntent(OnboardingPreferencesIntent.RequestAll)
            }
        },
        actionEnabled = !uiState.isRequesting,
        secondaryActionText = stringResource(R.string.ui_onboard_preferences_action_skip),
        secondaryActionEnabled = !uiState.isRequesting,
        onSecondaryActionClick = { onIntent(OnboardingPreferencesIntent.Complete) },
    ) {
        SettingsGroupCard(
            title = stringResource(R.string.ui_onboard_preferences_permissions_group),
        ) {
            PermissionRow(
                icon = Icons.Rounded.PhoneAndroid,
                title = stringResource(R.string.ui_onboard_preferences_phone_use_title),
                summary = stringResource(R.string.ui_onboard_preferences_phone_use_desc),
                granted = uiState.phoneUseGranted,
                enabled = !uiState.isRequesting,
                onGrantClick = { onIntent(OnboardingPreferencesIntent.RequestPhoneUse) },
            )
            SettingsItemDivider()
            PermissionRow(
                icon = Icons.Rounded.Layers,
                title = stringResource(R.string.ui_onboard_preferences_floating_ball_title),
                summary = stringResource(R.string.ui_onboard_preferences_floating_ball_desc),
                granted = uiState.overlayGranted,
                enabled = !uiState.isRequesting,
                onGrantClick = { onIntent(OnboardingPreferencesIntent.RequestFloatingBall) },
            )
            SettingsItemDivider()
            PermissionRow(
                icon = Icons.Rounded.Notifications,
                title = stringResource(R.string.ui_onboard_preferences_notification_title),
                summary = stringResource(R.string.ui_onboard_preferences_notification_desc),
                granted = uiState.notificationGranted,
                enabled = !uiState.isRequesting,
                onGrantClick = {
                    onIntent(OnboardingPreferencesIntent.RequestResidentNotification)
                },
            )
            SettingsItemDivider()
            PermissionRow(
                icon = Icons.Rounded.Folder,
                title = stringResource(R.string.ui_onboard_preferences_file_title),
                summary = stringResource(R.string.ui_onboard_preferences_file_desc),
                granted = uiState.storageGranted,
                enabled = !uiState.isRequesting,
                onGrantClick = { onIntent(OnboardingPreferencesIntent.RequestFileAccess) },
            )
        }

        SettingsGroupCard(
            title = stringResource(R.string.ui_onboard_preferences_security_group),
        ) {
            SettingsListItem(
                title = stringResource(R.string.ui_onboard_preferences_rule_add_action),
                summary = stringResource(R.string.ui_onboard_preferences_security_hint),
                trailingContent = {
                    IconButton(onClick = { onIntent(OnboardingPreferencesIntent.ShowAddRuleDialog) }) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = stringResource(
                                R.string.ui_onboard_preferences_rule_add_action
                            ),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                onClick = { onIntent(OnboardingPreferencesIntent.ShowAddRuleDialog) },
            )
            uiState.securityPatterns.forEach { pattern ->
                SettingsItemDivider()
                SettingsListItem(
                    title = pattern,
                    trailingContent = {
                        IconButton(
                            onClick = {
                                onIntent(OnboardingPreferencesIntent.RemoveRule(pattern))
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.RemoveCircleOutline,
                                contentDescription = stringResource(
                                    R.string.ui_onboard_preferences_rule_delete,
                                    pattern,
                                ),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                )
            }
        }
    }

    InputLiquidDialog(
        visible = uiState.addRuleDialogVisible,
        onDismissRequest = { onIntent(OnboardingPreferencesIntent.DismissAddRuleDialog) },
        title = stringResource(R.string.ui_onboard_preferences_rule_add_title),
        hint = stringResource(R.string.ui_onboard_preferences_rule_add_hint),
        value = uiState.newRuleText,
        onValueChange = { onIntent(OnboardingPreferencesIntent.SetNewRuleText(it)) },
        placeholder = stringResource(R.string.ui_onboard_preferences_rule_placeholder),
        positiveButtonText = stringResource(R.string.ui_onboard_preferences_rule_add_confirm),
        positiveEnabled = uiState.newRuleText.isNotBlank(),
        onPositiveClick = { onIntent(OnboardingPreferencesIntent.ConfirmAddRule) },
    )
}

/** 点击授权型设置项：左圆形图标 + 右侧「已开启 / 点击授权」，点击即申请。 */
@Composable
private fun PermissionRow(
    icon: ImageVector,
    title: String,
    summary: String,
    granted: Boolean,
    enabled: Boolean,
    onGrantClick: () -> Unit,
) {
    SettingsListItem(
        title = title,
        summary = summary,
        enabled = enabled,
        leadingContent = { RowLeadingIcon(icon) },
        trailingContent = {
            Text(
                text = stringResource(
                    if (granted) {
                        R.string.ui_onboard_preferences_granted
                    } else {
                        R.string.ui_onboard_preferences_grant_action
                    }
                ),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        },
        onClick = onGrantClick,
    )
}

@Composable
private fun RowLeadingIcon(icon: ImageVector) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
    }
}

private fun previewState() = OnboardingPreferencesUiState(
    accessibilityGranted = true,
    overlayGranted = true,
    notificationGranted = false,
    storageGranted = false,
)

@Preview(name = "Onboarding Permissions Light", showBackground = true, widthDp = 420, heightDp = 900)
@Composable
private fun OnboardingPreferencesLightPreview() {
    BaseTheme(darkTheme = false, dynamicColor = false) {
        Surface(modifier = Modifier.fillMaxSize()) {
            ProvideLiquidScreenContentForPreview(topPadding = 0.dp) {
                OnboardingPreferencesContent(uiState = previewState(), onIntent = {})
            }
        }
    }
}

@Preview(
    name = "Onboarding Permissions Dark",
    showBackground = true,
    widthDp = 420,
    heightDp = 900,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun OnboardingPreferencesDarkPreview() {
    BaseTheme(darkTheme = true, dynamicColor = false) {
        Surface(modifier = Modifier.fillMaxSize()) {
            ProvideLiquidScreenContentForPreview(topPadding = 0.dp) {
                OnboardingPreferencesContent(uiState = previewState(), onIntent = {})
            }
        }
    }
}
