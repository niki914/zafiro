package com.niki914.zafiro.app.ui.nexus.route

import androidx.compose.runtime.Composable
import com.niki914.zafiro.app.ui.nexus.content.HomePageContent
import com.niki914.zafiro.app.ui.nexus.nav.ConversationHistoryPage
import com.niki914.zafiro.app.ui.nexus.nav.NexusPage
import com.niki914.zafiro.app.ui.nexus.nav.SettingsHomePage

@Composable
internal fun HomePageRoute(
    onPush: (NexusPage) -> Unit,
    onPushFromLeft: (NexusPage) -> Unit,
    selectedConversationId: String?,
    onConversationSelectionConsumed: (String) -> Unit,
    onActiveConversationChanged: (String?, String?) -> Unit,
) {
    HomePageContent(
        selectedConversationId = selectedConversationId,
        onConversationSelectionConsumed = onConversationSelectionConsumed,
        onActiveConversationChanged = onActiveConversationChanged,
        onOpenHistory = {
            onPushFromLeft(ConversationHistoryPage)
        },
        onOpenSettings = {
            onPush(SettingsHomePage)
        },
    )
}
