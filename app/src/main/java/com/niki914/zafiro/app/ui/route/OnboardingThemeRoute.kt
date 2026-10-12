package com.niki914.zafiro.app.ui.route

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.niki914.uikit.infra.component.SettingsDetailPageDefaults
import com.niki914.uikit.infra.component.TintLiquidButton
import com.niki914.uikit.infra.liquidScreenActionButtonBottomPadding
import com.niki914.uikit.infra.liquidScreenBottomPadding
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.content.ThemeSettingsContent
import com.niki914.zafiro.app.ui.nav.ProviderPickPage
import com.niki914.zafiro.app.ui.nav.ZafiroPage

/** onboarding 第二步：先定外观与语言，再选提供商。选项选中即落盘，按钮只负责推进流程。 */
@Composable
internal fun OnboardingThemeRoute(
    onPush: (ZafiroPage) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        ThemeSettingsContent(
            onPush = onPush,
            // 为吸底按钮留位，否则最后一张卡片会被盖住
            contentBottomPadding = liquidScreenBottomPadding() +
                    SettingsDetailPageDefaults.RootVerticalSpacing +
                    SettingsDetailPageDefaults.ActionButtonReservedHeight,
        )
        TintLiquidButton(
            text = stringResource(R.string.ui_onboard_configure_next),
            onClick = { onPush(ProviderPickPage) },
            buttonHeight = SettingsDetailPageDefaults.ActionButtonReservedHeight,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(
                    start = SettingsDetailPageDefaults.HorizontalPadding,
                    end = SettingsDetailPageDefaults.HorizontalPadding,
                    bottom = liquidScreenActionButtonBottomPadding(),
                ),
        )
    }
}
