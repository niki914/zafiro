package com.niki914.zafiro.app.ui.model

import com.niki914.uikit.base.ComposeMVIViewModel
import com.niki914.uikit.base.ThemeStyle
import com.niki914.uikit.base.UiStylePart

data class ThemeStylesUiState(
    val unifiedDialogVisible: Boolean = false,
    val activePartDialog: UiStylePart? = null,
)

sealed interface ThemeStylesIntent {
    data object OpenUnifiedDialog : ThemeStylesIntent
    data object DismissUnifiedDialog : ThemeStylesIntent
    data class SelectUnifiedStyle(val style: ThemeStyle) : ThemeStylesIntent

    data class OpenPartDialog(val part: UiStylePart) : ThemeStylesIntent
    data object DismissPartDialog : ThemeStylesIntent
    data class SelectPartStyle(val part: UiStylePart, val style: ThemeStyle) : ThemeStylesIntent
}

class ThemeStylesViewModel :
    ComposeMVIViewModel<ThemeStylesIntent, ThemeStylesUiState, Nothing>() {

    override fun initUiState(): ThemeStylesUiState = ThemeStylesUiState()

    override suspend fun handleIntent(intent: ThemeStylesIntent) {
        when (intent) {
            ThemeStylesIntent.OpenUnifiedDialog ->
                updateState { copy(unifiedDialogVisible = true) }

            ThemeStylesIntent.DismissUnifiedDialog ->
                updateState { copy(unifiedDialogVisible = false) }

            is ThemeStylesIntent.SelectUnifiedStyle -> {
                updateState { copy(unifiedDialogVisible = false) }
                ThemeController.setUnifiedThemeStyle(intent.style)
            }

            is ThemeStylesIntent.OpenPartDialog ->
                updateState { copy(activePartDialog = intent.part) }

            ThemeStylesIntent.DismissPartDialog ->
                updateState { copy(activePartDialog = null) }

            is ThemeStylesIntent.SelectPartStyle -> {
                updateState { copy(activePartDialog = null) }
                ThemeController.setPartStyle(intent.part, intent.style)
            }
        }
    }
}
