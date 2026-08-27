package com.niki914.zafiro.app.ui

import androidx.compose.runtime.Composable
import com.niki914.uikit.infra.nav.NavigationEntry
import com.niki914.zafiro.app.ui.content.PyToolDetailContent
import com.niki914.zafiro.app.ui.model.StartupAssistantUi
import com.niki914.zafiro.app.ui.nav.ConfigurePage
import com.niki914.zafiro.app.ui.nav.BuiltinToolGroupDetailPage
import com.niki914.zafiro.app.ui.nav.ConversationHistoryPage
import com.niki914.zafiro.app.ui.nav.DonePage
import com.niki914.zafiro.app.ui.nav.ExecutionRuleDetailPage
import com.niki914.zafiro.app.ui.nav.HomePage
import com.niki914.zafiro.app.ui.nav.McpServerDetailPage
import com.niki914.zafiro.app.ui.nav.ZafiroPage
import com.niki914.zafiro.app.ui.nav.ProviderPickPage
import com.niki914.zafiro.app.ui.nav.SettingsConfigurePage
import com.niki914.zafiro.app.ui.nav.SettingsDetailPage
import com.niki914.zafiro.app.ui.nav.SettingsHomePage
import com.niki914.zafiro.app.ui.nav.SettingsProviderPickPage
import com.niki914.zafiro.app.ui.nav.SkillDetailPage
import com.niki914.zafiro.app.ui.nav.PyToolDetailPage
import com.niki914.zafiro.app.ui.nav.StartupPage
import com.niki914.zafiro.app.ui.nav.TakeoverRuleDetailPage
import com.niki914.zafiro.app.ui.route.ConfigurePageRoute
import com.niki914.zafiro.app.ui.route.BuiltinToolGroupDetailRoute
import com.niki914.zafiro.app.ui.route.ConversationHistoryPageRoute
import com.niki914.zafiro.app.ui.route.DonePageRoute
import com.niki914.zafiro.app.ui.route.ExecutionRuleDetailRoute
import com.niki914.zafiro.app.ui.route.HomePageRoute
import com.niki914.zafiro.app.ui.route.McpServerDetailRoute
import com.niki914.zafiro.app.ui.route.ProviderPickPageRoute
import com.niki914.zafiro.app.ui.route.SettingsConfigurePageRoute
import com.niki914.zafiro.app.ui.route.SettingsDetailPageRoute
import com.niki914.zafiro.app.ui.route.SettingsHomePageRoute
import com.niki914.zafiro.app.ui.route.SettingsProviderPickPageRoute
import com.niki914.zafiro.app.ui.route.SkillDetailRoute
import com.niki914.zafiro.app.ui.route.StartupPageRoute
import com.niki914.zafiro.app.ui.route.TakeoverRuleDetailRoute

@Composable
fun ZafiroPageContent(
    entry: NavigationEntry<ZafiroPage>,
    startupAssistantUi: StartupAssistantUi,
    onPush: (ZafiroPage) -> Unit,
    onPushFromLeft: (ZafiroPage) -> Unit,
    onPop: () -> Unit,
    onPopMultiple: (Int) -> Unit,
    onPopToRight: () -> Unit,
    onResetTo: (ZafiroPage) -> Unit,
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

        is SkillDetailPage -> SkillDetailRoute(
            page = page,
            onBack = onPop,
        )

        is PyToolDetailPage -> PyToolDetailContent(
            page = page,
            onBack = onPop,
        )

        is BuiltinToolGroupDetailPage -> BuiltinToolGroupDetailRoute(
            page = page,
            onBack = onPop,
        )
    }
}
