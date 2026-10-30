package com.niki914.zafiro.app.ui.home.header

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import com.niki914.uikit.menu.ZafiroDropdownMenu
import com.niki914.uikit.menu.ZafiroMenuAlignment
import com.niki914.uikit.menu.ZafiroMenuItem
import com.niki914.zafiro.app.ui.model.home.HomeChatUiState

@Composable
internal fun HomeConfigPopover(
    uiState: HomeChatUiState,
    anchorBounds: Rect?,
    onDismissRequest: () -> Unit,
    onSelectConfig: (String) -> Unit,
) {
    val configs = remember(uiState.savedConfigs, uiState.activeConfigId) {
        val activeId = uiState.activeConfigId
        if (activeId.isNullOrBlank()) {
            uiState.savedConfigs
        } else {
            val (active, rest) = uiState.savedConfigs.partition { it.id == activeId }
            active + rest
        }
    }

    ZafiroDropdownMenu(
        expanded = uiState.isConfigMenuVisible,
        onDismissRequest = onDismissRequest,
        anchorBounds = anchorBounds,
        alignment = ZafiroMenuAlignment.BottomCenter,
        scrollable = configs.size > 5,
    ) {
        configs.forEach { config ->
            ZafiroMenuItem(
                title = config.name,
                trailingText = config.modelId.takeIf { it != config.name },
                checked = config.id == uiState.activeConfigId,
                onClick = { onSelectConfig(config.id) },
            )
        }
    }
}
