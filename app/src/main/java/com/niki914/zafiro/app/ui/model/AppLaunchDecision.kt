package com.niki914.zafiro.app.ui.model

import com.niki914.zafiro.app.ui.nav.HomePage
import com.niki914.zafiro.app.ui.nav.NexusPage
import com.niki914.zafiro.app.ui.nav.StartupPage
import com.niki914.zafiro.repo.XRepo

data class AppLaunchDecision(
    val initialPage: NexusPage,
    val onboardingCompleted: Boolean,
) {
    companion object {
        suspend fun resolve(
            startupAssistantUi: StartupAssistantUi,
        ): AppLaunchDecision {
            val onboardingCompleted = XRepo.onboardingCompleted()
            val startupPage = when (startupAssistantUi) {
                StartupAssistantUi.Breeno,
                StartupAssistantUi.XiaoAi,
                StartupAssistantUi.ChatOnly -> StartupPage
            }
            val initialPage = if (onboardingCompleted) {
                HomePage
            } else {
                startupPage
            }
            return AppLaunchDecision(
                initialPage = initialPage,
                onboardingCompleted = onboardingCompleted
            )
        }
    }
}
