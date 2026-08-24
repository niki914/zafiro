package com.niki914.zafiro.app.ui.nexus.route

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import com.niki914.zafiro.app.ui.nexus.content.DonePageContent
import com.niki914.zafiro.app.ui.nexus.nav.HomePage
import com.niki914.zafiro.app.ui.nexus.nav.NexusPage
import com.niki914.zafiro.repo.XRepo
import kotlinx.coroutines.launch

@Composable
internal fun DonePageRoute(
    onResetTo: (NexusPage) -> Unit,
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
