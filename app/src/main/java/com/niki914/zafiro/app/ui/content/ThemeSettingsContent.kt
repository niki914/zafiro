package com.niki914.zafiro.app.ui.content

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.niki914.uikit.base.LocalAppDarkTheme
import com.niki914.uikit.infra.component.SettingsListPageContent
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.model.ThemeController
import com.niki914.zafiro.app.ui.model.ThemeMode
import kotlinx.coroutines.launch

/** 主题预设种子色（ARGB），顺序与 [themeColorLabelRes] 一一对应。 */
internal val ThemeSeedColors: List<Int> = listOf(
    0xFFFEB4A7.toInt(), 0xFFFFB3C0.toInt(), 0xFFFCAAFF.toInt(), 0xFFB9C3FF.toInt(),
    0xFF62D3FF.toInt(), 0xFF44D9F1.toInt(), 0xFF52DBC9.toInt(), 0xFF78DD77.toInt(),
    0xFF9FD75C.toInt(), 0xFFC1D02D.toInt(), 0xFFFABD00.toInt(), 0xFFFFB86E.toInt(),
)

internal val ThemeColorLabelRes: List<Int> = listOf(
    R.string.ui_theme_color_rose,
    R.string.ui_theme_color_blossom,
    R.string.ui_theme_color_lavender,
    R.string.ui_theme_color_blue,
    R.string.ui_theme_color_sky,
    R.string.ui_theme_color_cyan,
    R.string.ui_theme_color_mint,
    R.string.ui_theme_color_green,
    R.string.ui_theme_color_lime,
    R.string.ui_theme_color_lemon,
    R.string.ui_theme_color_amber,
    R.string.ui_theme_color_sunset,
)

@Composable
fun ThemeSettingsContent() {
    val scope = rememberCoroutineScope()
    val prefs = ThemeController.prefs
    val isDarkTheme = LocalAppDarkTheme.current

    val modeOptions = listOf(
        Triple(ThemeMode.System, Icons.Rounded.BrightnessAuto, R.string.ui_theme_mode_system),
        Triple(ThemeMode.Light, Icons.Rounded.LightMode, R.string.ui_theme_mode_light),
        Triple(ThemeMode.Dark, Icons.Rounded.DarkMode, R.string.ui_theme_mode_dark),
    )

    SettingsListPageContent {
        SelectionGroupCard(
            options = modeOptions.map { (mode, icon, labelRes) ->
                SelectionOption(
                    id = mode.storageKey,
                    title = stringResource(labelRes),
                    selected = prefs.mode == mode,
                    onClick = { scope.launch { ThemeController.setMode(mode) } },
                    leadingIconVector = icon,
                )
            },
            isDarkTheme = isDarkTheme,
        )

        SelectionGroupCard(
            options = buildList {
                add(
                    SelectionOption(
                        id = "dynamic",
                        title = stringResource(R.string.ui_theme_color_dynamic),
                        selected = prefs.seedColor == null,
                        onClick = { scope.launch { ThemeController.setSeedColor(null) } },
                        leadingIconVector = Icons.Rounded.Palette,
                    )
                )
                ThemeSeedColors.forEachIndexed { index, argb ->
                    add(
                        SelectionOption(
                            id = "seed-$argb",
                            title = stringResource(ThemeColorLabelRes[index]),
                            leadingSwatchColor = Color(argb),
                            selected = prefs.seedColor == argb,
                            onClick = { scope.launch { ThemeController.setSeedColor(argb) } },
                        )
                    )
                }
            },
            isDarkTheme = isDarkTheme,
        )
    }
}
