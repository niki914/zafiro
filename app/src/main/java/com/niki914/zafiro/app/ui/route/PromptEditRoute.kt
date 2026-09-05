package com.niki914.zafiro.app.ui.route

import androidx.compose.runtime.Composable
import com.niki914.zafiro.app.ui.content.PromptEditContent

@Composable
internal fun PromptEditRoute(
    onBack: () -> Unit,
) {
    PromptEditContent(
        onBack = onBack,
    )
}
