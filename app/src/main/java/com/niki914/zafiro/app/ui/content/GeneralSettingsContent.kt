package com.niki914.zafiro.app.ui.content

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.core.os.LocaleListCompat
import com.niki914.logging.Logger
import com.niki914.uikit.infra.component.settings.SettingsPageSpec
import com.niki914.uikit.infra.component.settings.SettingsRowAction
import com.niki914.uikit.infra.component.settings.SettingsRowSpec
import com.niki914.uikit.infra.component.settings.SettingsSectionLayout
import com.niki914.uikit.infra.component.settings.SettingsSectionSpec
import com.niki914.uikit.infra.component.settings.SettingsSpecPageContent
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.model.ThemeController
import com.niki914.zafiro.app.ui.model.ThemeMode
import com.niki914.zafiro.app.ui.nav.ThemeSettingsPage
import com.niki914.zafiro.app.ui.nav.ZafiroPage
import com.niki914.zafiro.repo.XRepo
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * General Settings：语言（用户指定优先，空 = 跟随系统）+ 冷启动是否载入上次对话。
 * 容器与 About 同款（SettingsSpecPageContent + GroupedCard）。
 */
private const val LANGUAGE_ROW_ID = "general.language"
private const val APPEARANCE_ROW_ID = "general.appearance"
private const val LOAD_LAST_ROW_ID = "general.load_last"

private const val LANGUAGE_TAG_ZH_CN = "zh-CN"
private const val LANGUAGE_TAG_ZH_TW = "zh-TW"
private const val LANGUAGE_TAG_EN = "en"
private const val LANGUAGE_TAG_ES = "es"
private const val LANGUAGE_TAG_JA = "ja"

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
    )
}

@Composable
fun GeneralSettingsContent(onPush: (ZafiroPage) -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var savedLanguageTag by rememberSaveable { mutableStateOf<String?>(null) }
    var loadLastConversation by rememberSaveable { mutableStateOf(false) }
    var showLanguageDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runCatching {
            savedLanguageTag = XRepo.languageTag()
            loadLastConversation = XRepo.loadLastConversationOnStartup()
        }.onFailure {
            Logger.w("niki914_nexus_GeneralSettings", "load failed ${it.message}")
        }
    }

    val selectedLabel = languageOptions()
        .firstOrNull { it.tag == savedLanguageTag }
        ?.label
        ?: stringResource(R.string.ui_settings_general_language_follow_system)

    val spec = SettingsPageSpec(
        description = null,
        sections = listOf(
            SettingsSectionSpec(
                layout = SettingsSectionLayout.GroupedCard,
                rows = listOf(
                    SettingsRowSpec.Navigation(
                        id = LANGUAGE_ROW_ID,
                        title = stringResource(R.string.ui_settings_general_language),
                        currentState = selectedLabel,
                    ),
                    SettingsRowSpec.Navigation(
                        id = APPEARANCE_ROW_ID,
                        title = stringResource(R.string.ui_settings_appearance),
                        currentState = appearanceSummary(),
                    ),
                    SettingsRowSpec.Toggle(
                        id = LOAD_LAST_ROW_ID,
                        title = stringResource(R.string.ui_settings_general_load_last_conversation),
                        checked = loadLastConversation,
                    ),
                ),
            ),
        ),
    )

    SettingsSpecPageContent(
        spec = spec,
        onAction = { action ->
            when (action) {
                is SettingsRowAction.Navigate ->
                    if (action.id == LANGUAGE_ROW_ID) {
                        showLanguageDialog = true
                    } else if (action.id == APPEARANCE_ROW_ID) {
                        onPush(ThemeSettingsPage)
                    }

                is SettingsRowAction.ToggleChanged ->
                    if (action.id == LOAD_LAST_ROW_ID) {
                        loadLastConversation = action.checked
                        scope.launch {
                            XRepo.setLoadLastConversationOnStartup(action.checked)
                        }
                    }

                else -> Unit
            }
        },
    )

    val labeledOptions = languageOptions()
    SingleChoiceLiquidDialog(
        visible = showLanguageDialog,
        onDismissRequest = { showLanguageDialog = false },
        title = stringResource(R.string.ui_settings_general_language),
        options = labeledOptions,
        selectedId = savedLanguageTag ?: "",
        optionId = LanguageOption::tag,
        optionLabel = LanguageOption::label,
        onSelect = { option ->
            savedLanguageTag = option.tag
            showLanguageDialog = false
            // 必须同步落盘：setApplicationLocales 触发 recreate 会取消协程作用域
            runBlocking {
                XRepo.setLanguageTag(option.tag)
            }
            AppCompatDelegate.setApplicationLocales(
                if (option.tag.isBlank()) {
                    LocaleListCompat.getEmptyLocaleList()
                } else {
                    LocaleListCompat.forLanguageTags(option.tag)
                },
            )
        },
    )
}

@Composable
private fun appearanceSummary(): String {
    val prefs = ThemeController.prefs
    val modeLabel = when (prefs.mode) {
        ThemeMode.System -> stringResource(R.string.ui_theme_mode_system)
        ThemeMode.Light -> stringResource(R.string.ui_theme_mode_light)
        ThemeMode.Dark -> stringResource(R.string.ui_theme_mode_dark)
    }
    val colorLabel = prefs.seedColor
        ?.let { seed -> ThemeSeedColors.indexOf(seed).takeIf { it >= 0 } }
        ?.let { stringResource(ThemeColorLabelRes[it]) }
        ?: stringResource(R.string.ui_theme_color_dynamic)
    return "$modeLabel · $colorLabel"
}
