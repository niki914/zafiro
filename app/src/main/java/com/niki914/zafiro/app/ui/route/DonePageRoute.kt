package com.niki914.zafiro.app.ui.route

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import com.niki914.zafiro.app.ui.content.DonePageContent
import com.niki914.zafiro.app.ui.nav.HomePage
import com.niki914.zafiro.app.ui.nav.ZafiroPage
import com.niki914.zafiro.repo.XRepo
import kotlinx.coroutines.launch

@Composable
internal fun DonePageRoute(
    onResetTo: (ZafiroPage) -> Unit,
) {
    val scope = rememberCoroutineScope()

    DonePageContent(
        onEnterHome = {
            scope.launch {
                completeOnboarding()
                onResetTo(HomePage)
            }
        },
    )
}

private suspend fun completeOnboarding() {
    if (XRepo.onboardingCompleted()) {
        return
    }
    XRepo.setOnboardingCompleted(true)
}
