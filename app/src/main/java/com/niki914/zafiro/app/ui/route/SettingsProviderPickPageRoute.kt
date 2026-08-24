package com.niki914.zafiro.app.ui.route

import androidx.compose.runtime.Composable
import com.niki914.zafiro.app.ui.content.SelectionOption
import com.niki914.zafiro.app.ui.content.SelectionPageContent
import com.niki914.zafiro.app.ui.model.ProviderSpecs
import com.niki914.zafiro.app.ui.nav.NexusPage
import com.niki914.zafiro.app.ui.nav.SettingsConfigurePage
import com.niki914.zafiro.app.ui.nav.TextTitle

@Composable
internal fun SettingsProviderPickPageRoute(
    onPush: (NexusPage) -> Unit,
) {
    SelectionPageContent(
        options = ProviderSpecs.all.map { spec ->
            val colors = providerButtonColors(spec)
            SelectionOption(
                id = spec.id,
                title = spec.brandName,
                leadingIconRes = spec.iconRes,
                tintLeadingIcon = spec.tintIcon,
                darkContainerColor = colors.darkContainerColor,
                lightContainerColor = colors.lightContainerColor,
                darkContentColor = colors.darkContentColor,
                lightContentColor = colors.lightContentColor,
                onClick = {
                    onPush(
                        SettingsConfigurePage(
                            providerId = spec.id,
                            explicitTitleSpec = TextTitle(spec.brandName),
                        ),
                    )
                },
            )
        },
    )
}
