package com.niki914.zafiro.app.ui.home.header

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.PageChromeContribution
import com.niki914.zafiro.app.ui.PageChromeMenuItem
import com.niki914.zafiro.app.ui.RegisterPageChrome
import com.niki914.zafiro.app.ui.nav.TopBarActionSpec

@Composable
internal fun HomeTopBarContribution(
    isConfigMenuVisible: Boolean,
    onDismissConfigMenu: () -> Unit,
    onOpenHistory: () -> Unit,
    onNewConversation: () -> Unit,
    onOpenSettings: () -> Unit,
    titleContent: @Composable () -> Unit,
) {
    val newConversationMenuLabel = stringResource(R.string.ui_home_menu_new_conversation)
    val settingsMenuLabel = stringResource(R.string.ui_settings_menu_entry)

    val pageChromeContribution = remember(
        newConversationMenuLabel,
        settingsMenuLabel,
        titleContent,
        isConfigMenuVisible,
    ) {
        PageChromeContribution(
            titleContent = titleContent,
            isTitleOccluded = isConfigMenuVisible,
            actionsVisible = true,
            leftAction = TopBarActionSpec(
                icon = Icons.Default.History,
                onClick = {
                    if (isConfigMenuVisible) {
                        onDismissConfigMenu()
                    }
                    onOpenHistory()
                },
                contentDescription = "History Button",
            ),
            menuItems = listOf(
                PageChromeMenuItem(
                    key = "new_conversation",
                    title = newConversationMenuLabel,
                    onClick = {
                        if (isConfigMenuVisible) {
                            onDismissConfigMenu()
                        }
                        onNewConversation()
                    },
                ),
                PageChromeMenuItem(
                    key = "settings",
                    title = settingsMenuLabel,
                    onClick = {
                        if (isConfigMenuVisible) {
                            onDismissConfigMenu()
                        }
                        onOpenSettings()
                    },
                ),
            ),
        )
    }

    RegisterPageChrome(pageChromeContribution)
}
