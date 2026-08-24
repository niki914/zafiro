package com.niki914.zafiro.app.ui.nexus.route

import androidx.compose.runtime.Composable
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.nexus.content.SettingsHomePageContent
import com.niki914.zafiro.app.ui.nexus.nav.NexusPage
import com.niki914.zafiro.app.ui.nexus.nav.NexusSettingsGroup
import com.niki914.zafiro.app.ui.nexus.nav.PageTitleSpec
import com.niki914.zafiro.app.ui.nexus.nav.ResTitle
import com.niki914.zafiro.app.ui.nexus.nav.SettingsDetailPage

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
