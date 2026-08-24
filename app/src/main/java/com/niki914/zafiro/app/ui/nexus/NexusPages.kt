package com.niki914.zafiro.app.ui.nexus

import androidx.compose.runtime.Composable
import com.niki914.uikit.infra.nav.NavigationEntry
import com.niki914.zafiro.app.ui.nexus.model.StartupAssistantUi
import com.niki914.zafiro.app.ui.nexus.nav.ConfigurePage
import com.niki914.zafiro.app.ui.nexus.nav.ConversationHistoryPage
import com.niki914.zafiro.app.ui.nexus.nav.CustomToolDetailPage
import com.niki914.zafiro.app.ui.nexus.nav.DonePage
import com.niki914.zafiro.app.ui.nexus.nav.ExecutionRuleDetailPage
import com.niki914.zafiro.app.ui.nexus.nav.HomePage
import com.niki914.zafiro.app.ui.nexus.nav.McpServerDetailPage
import com.niki914.zafiro.app.ui.nexus.nav.NexusPage
import com.niki914.zafiro.app.ui.nexus.nav.ProviderPickPage
import com.niki914.zafiro.app.ui.nexus.nav.SettingsConfigurePage
import com.niki914.zafiro.app.ui.nexus.nav.SettingsDetailPage
import com.niki914.zafiro.app.ui.nexus.nav.SettingsHomePage
import com.niki914.zafiro.app.ui.nexus.nav.SettingsProviderPickPage
import com.niki914.zafiro.app.ui.nexus.nav.SkillDetailPage
import com.niki914.zafiro.app.ui.nexus.nav.StartupPage
import com.niki914.zafiro.app.ui.nexus.nav.TakeoverRuleDetailPage
import com.niki914.zafiro.app.ui.nexus.route.ConfigurePageRoute
import com.niki914.zafiro.app.ui.nexus.route.ConversationHistoryPageRoute
import com.niki914.zafiro.app.ui.nexus.route.CustomToolDetailRoute
import com.niki914.zafiro.app.ui.nexus.route.DonePageRoute
import com.niki914.zafiro.app.ui.nexus.route.ExecutionRuleDetailRoute
import com.niki914.zafiro.app.ui.nexus.route.HomePageRoute
import com.niki914.zafiro.app.ui.nexus.route.McpServerDetailRoute
import com.niki914.zafiro.app.ui.nexus.route.ProviderPickPageRoute
import com.niki914.zafiro.app.ui.nexus.route.SettingsConfigurePageRoute
import com.niki914.zafiro.app.ui.nexus.route.SettingsDetailPageRoute
import com.niki914.zafiro.app.ui.nexus.route.SettingsHomePageRoute
import com.niki914.zafiro.app.ui.nexus.route.SettingsProviderPickPageRoute
import com.niki914.zafiro.app.ui.nexus.route.SkillDetailRoute
import com.niki914.zafiro.app.ui.nexus.route.StartupPageRoute
import com.niki914.zafiro.app.ui.nexus.route.TakeoverRuleDetailRoute

@Composable
fun NexusPageContent(
    entry: NavigationEntry<NexusPage>,
    startupAssistantUi: StartupAssistantUi,
    onPush: (NexusPage) -> Unit,
    onPushFromLeft: (NexusPage) -> Unit,
    onPop: () -> Unit,
    onPopMultiple: (Int) -> Unit,
    onPopToRight: () -> Unit,
    onResetTo: (NexusPage) -> Unit,
    selectedConversationId: String?,
    onConversationSelected: (String) -> Unit,
    onConversationSelectionConsumed: (String) -> Unit,
    activeConversationId: String?,
    activeConversationTitle: String?,
    onActiveConversationChanged: (String?, String?) -> Unit,
    onCurrentConversationDeleted: suspend (String) -> Unit,
) {
    when (val page = entry.page) {
        StartupPage -> StartupPageRoute(
            startupAssistantUi = startupAssistantUi,
            onPush = onPush,
        )

        ProviderPickPage -> ProviderPickPageRoute(
            onPush = onPush,
        )

        SettingsProviderPickPage -> SettingsProviderPickPageRoute(
            onPush = onPush,
        )

        is ConfigurePage -> ConfigurePageRoute(
            page = page,
            onPush = onPush,
        )

        is SettingsConfigurePage -> SettingsConfigurePageRoute(
            page = page,
            onBack = onPop,
            onResetToSettingsHome = {
                onPopMultiple(2)
            },
        )

        DonePage -> DonePageRoute(
            onResetTo = onResetTo,
        )

        HomePage -> HomePageRoute(
            onPush = onPush,
            onPushFromLeft = onPushFromLeft,
            selectedConversationId = selectedConversationId,
            onConversationSelectionConsumed = onConversationSelectionConsumed,
            onActiveConversationChanged = onActiveConversationChanged,
        )

        ConversationHistoryPage -> ConversationHistoryPageRoute(
            activeConversationId = activeConversationId,
            activeConversationTitle = activeConversationTitle,
            onBack = onPopToRight,
            onConversationSelected = { id ->
                onConversationSelected(id)
                onPopToRight()
            },
            onCurrentConversationDeleted = onCurrentConversationDeleted,
        )

        SettingsHomePage -> SettingsHomePageRoute(
            onPush = onPush,
        )

        is SettingsDetailPage -> SettingsDetailPageRoute(
            page = page,
            onPush = onPush,
            onBack = onPop,
        )

        is McpServerDetailPage -> McpServerDetailRoute(
            page = page,
            onBack = onPop,
        )

        is ExecutionRuleDetailPage -> ExecutionRuleDetailRoute(
            page = page,
            onBack = onPop,
        )

        is TakeoverRuleDetailPage -> TakeoverRuleDetailRoute(
            page = page,
            onBack = onPop,
        )

        is CustomToolDetailPage -> CustomToolDetailRoute(
            page = page,
            onBack = onPop,
        )

        is SkillDetailPage -> SkillDetailRoute(
            page = page,
            onBack = onPop,
        )
    }
}
