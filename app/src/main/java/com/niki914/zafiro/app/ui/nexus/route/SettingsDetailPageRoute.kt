package com.niki914.zafiro.app.ui.nexus.route

import androidx.compose.runtime.Composable
import com.niki914.zafiro.app.ui.nexus.content.SettingsDetailPageContent
import com.niki914.zafiro.app.ui.nexus.nav.NexusPage
import com.niki914.zafiro.app.ui.nexus.nav.SettingsDetailPage

@Composable
internal fun SettingsDetailPageRoute(
    page: SettingsDetailPage,
    onPush: (NexusPage) -> Unit,
    onBack: () -> Unit,
) {
    SettingsDetailPageContent(
        group = page.group,
        onPush = onPush,
        onBack = onBack,
    )
}
