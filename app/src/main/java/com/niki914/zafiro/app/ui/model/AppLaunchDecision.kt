package com.niki914.zafiro.app.ui.model

import com.niki914.zafiro.app.ui.nav.HomePage
import com.niki914.zafiro.app.ui.nav.ZafiroPage
import com.niki914.zafiro.app.ui.nav.StartupPage
import com.niki914.zafiro.repo.XRepo

data class AppLaunchDecision(
    val initialPage: ZafiroPage,
    val onboardingCompleted: Boolean,
    /** BCP-47 tag；空串 = 跟随系统。冷启动期间一次性应用，供 MainActivity 在 setContent 前调用。 */
    val languageTag: String,
) {
    companion object {
        suspend fun resolve(
            startupAssistantUi: StartupAssistantUi,
        ): AppLaunchDecision {
            val onboardingCompleted = XRepo.onboardingCompleted()
            val languageTag = XRepo.languageTag()
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
                onboardingCompleted = onboardingCompleted,
                languageTag = languageTag,
            )
        }
    }
}
