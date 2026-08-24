package com.niki914.zafiro.app.ui.route

import androidx.compose.runtime.Composable
import com.niki914.zafiro.app.ui.content.SettingsDetailPageContent
import com.niki914.zafiro.app.ui.nav.NexusPage
import com.niki914.zafiro.app.ui.nav.SettingsDetailPage

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
