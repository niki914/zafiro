package com.niki914.zafiro.app.ui.content

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.niki914.uikit.infra.component.settings.SettingsPageSpec
import com.niki914.uikit.infra.component.settings.SettingsRowSpec
import com.niki914.uikit.infra.component.settings.SettingsSectionLayout
import com.niki914.uikit.infra.component.settings.SettingsSectionSpec
import com.niki914.uikit.infra.component.settings.SettingsSpecPageContent
import com.niki914.zafiro.app.R

private const val EXPORT_ROW_ID = "backup.export"
private const val IMPORT_ROW_ID = "backup.import"

/**
 * 备份与恢复页壳。导出、导入只展示，这一轮不打开选择器。
 */
@Composable
fun BackupSettingsContent() {
    val spec = SettingsPageSpec(
        description = stringResource(R.string.ui_settings_backup_description),
        sections = listOf(
            SettingsSectionSpec(
                layout = SettingsSectionLayout.GroupedCard,
                rows = listOf(
                    SettingsRowSpec.Navigation(
                        id = EXPORT_ROW_ID,
                        title = stringResource(R.string.ui_settings_backup_export),
                        summary = stringResource(R.string.ui_settings_backup_export_summary),
                    ),
                    SettingsRowSpec.Navigation(
                        id = IMPORT_ROW_ID,
                        title = stringResource(R.string.ui_settings_backup_import),
                        summary = stringResource(R.string.ui_settings_backup_import_summary),
                    ),
                ),
            ),
        ),
    )

    SettingsSpecPageContent(
        spec = spec,
        onAction = { _ -> },
    )
}
