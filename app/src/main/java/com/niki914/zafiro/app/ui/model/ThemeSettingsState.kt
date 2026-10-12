package com.niki914.zafiro.app.ui.model

import com.niki914.uikit.base.ComposeMVIViewModel
import com.niki914.zafiro.repo.XRepo

/**
 * 外观与语言页。主题模式与配色由 [ThemeController] 持有（全局内存快照），本 VM 只管语言：
 * 语言需要落盘 + 应用 locales，跟主题的驱动方式不同。
 */
data class ThemeSettingsUiState(
    val languageTag: String = "",
    val languageDialogVisible: Boolean = false,
)

sealed interface ThemeSettingsIntent {
    data object Load : ThemeSettingsIntent
    data object OpenLanguageDialog : ThemeSettingsIntent
    data object DismissLanguageDialog : ThemeSettingsIntent
    data class SelectLanguage(val tag: String) : ThemeSettingsIntent
}

sealed interface ThemeSettingsEffect {
    data class ApplyApplicationLocales(val languageTag: String) : ThemeSettingsEffect
}

class ThemeSettingsViewModel :
    ComposeMVIViewModel<ThemeSettingsIntent, ThemeSettingsUiState, ThemeSettingsEffect>() {

    override fun initUiState(): ThemeSettingsUiState = ThemeSettingsUiState()

    override suspend fun handleIntent(intent: ThemeSettingsIntent) {
        when (intent) {
            ThemeSettingsIntent.Load -> loadLanguage()
            ThemeSettingsIntent.OpenLanguageDialog ->
                updateState { copy(languageDialogVisible = true) }

            ThemeSettingsIntent.DismissLanguageDialog ->
                updateState { copy(languageDialogVisible = false) }

            is ThemeSettingsIntent.SelectLanguage -> selectLanguage(intent.tag)
        }
    }

    private suspend fun loadLanguage() {
        val tag = XRepo.languageTag()
        updateState { copy(languageTag = tag) }
    }

    private suspend fun selectLanguage(tag: String) {
        updateState { copy(languageTag = tag, languageDialogVisible = false) }
        XRepo.setLanguageTag(tag)
        sendEffect(ThemeSettingsEffect.ApplyApplicationLocales(tag))
    }
}
