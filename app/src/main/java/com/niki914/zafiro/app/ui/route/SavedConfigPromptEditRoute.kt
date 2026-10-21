package com.niki914.zafiro.app.ui.route

import androidx.compose.runtime.Composable
import com.niki914.zafiro.app.ui.content.SavedConfigPromptEditContent
import com.niki914.zafiro.app.ui.nav.SavedConfigPromptEditPage

@Composable
internal fun SavedConfigPromptEditRoute(
    page: SavedConfigPromptEditPage,
    onBack: () -> Unit,
) {
    SavedConfigPromptEditContent(
        page = page,
        onBack = onBack,
    )
}
