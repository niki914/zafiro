package com.niki914.zafiro.app.ui.nexus.route

import androidx.compose.runtime.Composable
import com.niki914.zafiro.app.ui.nexus.content.SelectionOption
import com.niki914.zafiro.app.ui.nexus.content.SelectionPageContent
import com.niki914.zafiro.app.ui.nexus.model.ProviderSpecs
import com.niki914.zafiro.app.ui.nexus.nav.NexusPage
import com.niki914.zafiro.app.ui.nexus.nav.SettingsConfigurePage
import com.niki914.zafiro.app.ui.nexus.nav.TextTitle

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
