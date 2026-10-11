package com.niki914.zafiro.app.ui.route

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.niki914.zafiro.app.ui.content.DonePageContent
import com.niki914.zafiro.app.ui.nav.HomePage
import com.niki914.zafiro.app.ui.nav.OnboardingPreferencesPage
import com.niki914.zafiro.app.ui.nav.ZafiroPage
import com.niki914.zafiro.repo.XRepo

@Composable
internal fun DonePageRoute(
    onPush: (ZafiroPage) -> Unit,
    onResetTo: (ZafiroPage) -> Unit,
) {
    // 到达本页即视为 onboarding 完成：provider 已在上一页保存并置为 active，
    // 冷启动不该因为用户没点到"进入 Zafiro"而重新走一遍 onboarding。
    LaunchedEffect(Unit) {
        completeOnboarding()
    }

    DonePageContent(
        onEnterHome = { onResetTo(HomePage) },
        onOpenPreferences = {
            onPush(OnboardingPreferencesPage)
        },
    )
}

private suspend fun completeOnboarding() {
    if (XRepo.onboardingCompleted()) {
        return
    }
    XRepo.setOnboardingCompleted(true)
}
