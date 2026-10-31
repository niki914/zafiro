package com.niki914.zafiro.app.ui.content

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.core.os.LocaleListCompat
import com.niki914.uikit.base.LocalAppDarkTheme
import com.niki914.uikit.base.ThemeStyle
import com.niki914.uikit.dialog.ZafiroSingleChoiceDialog
import com.niki914.uikit.infra.component.settings.SettingsPageSpec
import com.niki914.uikit.infra.component.settings.SettingsRowAction
import com.niki914.uikit.infra.component.settings.SettingsRowLeadingIcon
import com.niki914.uikit.infra.component.settings.SettingsRowSpec
import com.niki914.uikit.infra.component.settings.SettingsSectionLayout
import com.niki914.uikit.infra.component.settings.SettingsSectionSpec
import com.niki914.uikit.infra.component.settings.SettingsSpecPageContent
import com.niki914.uikit.infra.nav.pageViewModel
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.model.ThemeController
import com.niki914.zafiro.app.ui.model.ThemeMode
import com.niki914.zafiro.app.ui.model.ThemeSettingsEffect
import com.niki914.zafiro.app.ui.model.ThemeSettingsIntent
import com.niki914.zafiro.app.ui.model.ThemeSettingsViewModel
import com.niki914.zafiro.app.ui.nav.MarkdownScalePage
import com.niki914.zafiro.app.ui.nav.ZafiroPage
import com.niki914.zafiro.repo.XRepo
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 外观与语言：深浅色模式、配色（[ThemeController] 驱动）+ 语言（[ThemeSettingsViewModel] 驱动）+ Markdown 缩放。
 *
 * 两项都是选中即落盘；onboarding 首次配置需要吸底按钮时，由调用方传 [contentBottomPadding] 留位。
 */
private const val LANGUAGE_ROW_ID = "theme.language"
private const val MARKDOWN_SCALE_ROW_ID = "theme.markdown_scale"
private const val THEME_STYLE_ROW_ID = "theme.style"

private const val LANGUAGE_TAG_ZH_CN = "zh-CN"
private const val LANGUAGE_TAG_ZH_TW = "zh-TW"
private const val LANGUAGE_TAG_EN = "en"
private const val LANGUAGE_TAG_ES = "es"
private const val LANGUAGE_TAG_JA = "ja"
private const val LANGUAGE_TAG_RU = "ru"
private const val LANGUAGE_TAG_PT_BR = "pt-BR"

/** 主题预设种子色（ARGB），顺序与 [ThemeColorLabelRes] 一一对应。 */
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

data class ThemeStyleOption(
    val style: ThemeStyle,
    val label: String,
)

data class LanguageOption(
    /** BCP-47 tag；空串 = 跟随系统。 */
    val tag: String,
    /** 选项自身语言的显示名（跟随系统项用 res）。 */
    val label: String,
)

@Composable
private fun languageOptions(): List<LanguageOption> {
    return listOf(
        LanguageOption(
            tag = "",
            label = stringResource(R.string.ui_settings_general_language_follow_system)
        ),
        LanguageOption(tag = LANGUAGE_TAG_ZH_CN, label = "简体中文"),
        LanguageOption(tag = LANGUAGE_TAG_ZH_TW, label = "繁體中文"),
        LanguageOption(tag = LANGUAGE_TAG_EN, label = "English"),
        LanguageOption(tag = LANGUAGE_TAG_ES, label = "Español"),
        LanguageOption(tag = LANGUAGE_TAG_JA, label = "日本語"),
        LanguageOption(tag = LANGUAGE_TAG_RU, label = "Русский"),
        LanguageOption(tag = LANGUAGE_TAG_PT_BR, label = "Português (Brasil)"),
    )
}

@Composable
fun ThemeSettingsContent(
    viewModel: ThemeSettingsViewModel = pageViewModel(),
    contentBottomPadding: Dp? = null,
    onPush: (ZafiroPage) -> Unit,
) {
    val uiState by viewModel.uiStateFlow.collectAsState()
    val scope = rememberCoroutineScope()
    val prefs = ThemeController.prefs
    val isDarkTheme = LocalAppDarkTheme.current
    val currentScale by XRepo.markdownScaleSetting.collectAsState()
    val currentScalePercent = (currentScale * 100).roundToInt()

    LaunchedEffect(Unit) {
        viewModel.sendIntent(ThemeSettingsIntent.Load)
    }

    LaunchedEffect(viewModel) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                is ThemeSettingsEffect.ApplyApplicationLocales -> {
                    AppCompatDelegate.setApplicationLocales(
                        if (effect.languageTag.isBlank()) {
                            LocaleListCompat.getEmptyLocaleList()
                        } else {
                            LocaleListCompat.forLanguageTags(effect.languageTag)
                        }
                    )
                }
            }
        }
    }

    val modeOptions = listOf(
        Triple(ThemeMode.System, Icons.Rounded.PhoneAndroid, R.string.ui_theme_mode_system),
        Triple(ThemeMode.Light, Icons.Rounded.LightMode, R.string.ui_theme_mode_light),
        Triple(ThemeMode.Dark, Icons.Rounded.DarkMode, R.string.ui_theme_mode_dark),
    )
    val languageOptionList = languageOptions()

    var themeStyleDialogVisible by rememberSaveable { mutableStateOf(false) }
    val themeStyleOptions = listOf(
        ThemeStyleOption(ThemeStyle.LiquidGlass, stringResource(R.string.ui_theme_style_liquid)),
        ThemeStyleOption(ThemeStyle.Material3, stringResource(R.string.ui_theme_style_md3)),
        ThemeStyleOption(ThemeStyle.Material3Expressive, stringResource(R.string.ui_theme_style_md3e)),
    )

    val spec = SettingsPageSpec(
        sections = listOf(
            SettingsSectionSpec(
                layout = SettingsSectionLayout.GroupedCard,
                rows = listOf(
                    SettingsRowSpec.Navigation(
                        id = LANGUAGE_ROW_ID,
                        title = stringResource(R.string.ui_settings_general_language),
                        currentState = languageOptionList
                            .firstOrNull { it.tag == uiState.languageTag }
                            ?.label,
                        leadingIcon = SettingsRowLeadingIcon.Vector(Icons.Rounded.Translate),
                    ),
                    SettingsRowSpec.Navigation(
                        id = MARKDOWN_SCALE_ROW_ID,
                        title = stringResource(R.string.ui_settings_markdown_scale_title),
                        currentState = if (currentScalePercent == 100) {
                            stringResource(R.string.ui_settings_markdown_scale_default)
                        } else {
                            "$currentScalePercent%"
                        },
                        leadingIcon = SettingsRowLeadingIcon.Vector(Icons.Rounded.FormatSize),
                    ),
                ),
            ),
            SettingsSectionSpec(
                layout = SettingsSectionLayout.GroupedCard,
                rows = listOf(
                    SettingsRowSpec.Navigation(
                        id = THEME_STYLE_ROW_ID,
                        title = stringResource(R.string.ui_settings_theme_style_title),
                        currentState = when (prefs.themeStyle) {
                            ThemeStyle.LiquidGlass -> stringResource(R.string.ui_theme_style_liquid)
                            ThemeStyle.Material3 -> stringResource(R.string.ui_theme_style_md3)
                            ThemeStyle.Material3Expressive -> stringResource(R.string.ui_theme_style_md3e)
                        },
                        leadingIcon = SettingsRowLeadingIcon.Vector(Icons.Rounded.BrightnessAuto),
                    ),
                ),
            ),
        ),
    )

    SettingsSpecPageContent(
        spec = spec,
        contentBottomPadding = contentBottomPadding,
        contentAfterSections = {
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
        },
        onAction = { action ->
            when (action) {
                is SettingsRowAction.Navigate -> {
                    when (action.id) {
                        THEME_STYLE_ROW_ID -> themeStyleDialogVisible = true
                        LANGUAGE_ROW_ID -> viewModel.sendIntent(ThemeSettingsIntent.OpenLanguageDialog)
                        MARKDOWN_SCALE_ROW_ID -> onPush(MarkdownScalePage)
                    }
                }
                else -> Unit
            }
        },
    )

    ZafiroSingleChoiceDialog(
        visible = themeStyleDialogVisible,
        onDismissRequest = { themeStyleDialogVisible = false },
        title = stringResource(R.string.ui_settings_theme_style_title),
        options = themeStyleOptions,
        selectedId = prefs.themeStyle.storageKey,
        optionId = { it.style.storageKey },
        optionLabel = ThemeStyleOption::label,
        onSelect = { option ->
            themeStyleDialogVisible = false
            if (option.style != prefs.themeStyle) {
                scope.launch { ThemeController.setThemeStyle(option.style) }
            }
        },
    )

    ZafiroSingleChoiceDialog(
        visible = uiState.languageDialogVisible,
        onDismissRequest = { viewModel.sendIntent(ThemeSettingsIntent.DismissLanguageDialog) },
        title = stringResource(R.string.ui_settings_general_language),
        options = languageOptionList,
        selectedId = uiState.languageTag,
        optionId = LanguageOption::tag,
        optionLabel = LanguageOption::label,
        onSelect = { option ->
            viewModel.sendIntent(ThemeSettingsIntent.SelectLanguage(option.tag))
        },
    )
}
