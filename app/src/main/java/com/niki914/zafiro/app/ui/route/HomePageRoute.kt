package com.niki914.zafiro.app.ui.route

import androidx.compose.runtime.Composable
import com.niki914.zafiro.app.ui.content.HomePageContent
import com.niki914.zafiro.app.ui.nav.ConversationHistoryPage
import com.niki914.zafiro.app.ui.nav.SettingsHomePage
import com.niki914.zafiro.app.ui.nav.ZafiroPage

@Composable
internal fun HomePageRoute(
    onPush: (ZafiroPage) -> Unit,
    onPushFromLeft: (ZafiroPage) -> Unit,
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
