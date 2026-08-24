package com.niki914.zafiro.app.ui.route

import androidx.compose.runtime.Composable
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.content.SettingsHomePageContent
import com.niki914.zafiro.app.ui.nav.NexusPage
import com.niki914.zafiro.app.ui.nav.NexusSettingsGroup
import com.niki914.zafiro.app.ui.nav.PageTitleSpec
import com.niki914.zafiro.app.ui.nav.ResTitle
import com.niki914.zafiro.app.ui.nav.SettingsDetailPage

@Composable
internal fun SettingsHomePageRoute(
    onPush: (NexusPage) -> Unit,
) {
    SettingsHomePageContent(
        onOpenGroup = { group ->
            onPush(
                SettingsDetailPage(
                    group = group,
                    explicitTitleSpec = settingsDetailTitleSpec(group),
                )
            )
        },
    )
}

private fun settingsDetailTitleSpec(group: NexusSettingsGroup): PageTitleSpec? {
    return when (group) {
        NexusSettingsGroup.Mcp -> ResTitle(R.string.ui_settings_mcp_config)
        else -> null
    }
}
