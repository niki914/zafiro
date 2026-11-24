package com.niki914.zafiro.app.ui.route

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.niki914.zafiro.app.ui.content.SelectionOption
import com.niki914.zafiro.app.ui.content.SelectionPageContent
import com.niki914.zafiro.app.ui.model.ProviderSpecs
import com.niki914.zafiro.app.ui.nav.SavedConfigDetailPage
import com.niki914.zafiro.app.ui.nav.ZafiroPage

@Composable
internal fun SettingsProviderPickPageRoute(
    onPush: (ZafiroPage) -> Unit,
) {
    SelectionPageContent(
        options = ProviderSpecs.all.map { spec ->
            val colors = providerButtonColorsOrNull(spec)
            SelectionOption(
                id = spec.id,
                title = spec.brandName,
                summary = spec.summaryRes?.let { stringResource(it) },
                leadingIconRes = spec.iconRes,
                tintLeadingIcon = spec.tintIcon,
                darkContainerColor = colors?.darkContainerColor,
                lightContainerColor = colors?.lightContainerColor,
                darkContentColor = colors?.darkContentColor,
                lightContentColor = colors?.lightContentColor,
                darkIconColor = colors?.darkIconColor,
                lightIconColor = colors?.lightIconColor,
                onClick = {
                    onPush(
                        SavedConfigDetailPage(
                            configId = null,
                            configName = spec.brandName,
                            providerId = spec.id,
                        ),
                    )
                },
            )
        },
    )
}
