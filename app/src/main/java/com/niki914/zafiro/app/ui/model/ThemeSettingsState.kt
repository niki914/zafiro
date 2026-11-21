package com.niki914.zafiro.app.ui.model

import com.niki914.uikit.base.ComposeMVIViewModel
import com.niki914.zafiro.repo.XRepo

/**
 * 外观与语言页。主题模式与配色由 [ThemeController] 持有（全局内存快照），本 VM 管理语言与模型选择器风格：
 * 语言需要落盘 + 应用 locales，模型选择器风格落盘驱动首页。
 */
data class ThemeSettingsUiState(
    val languageTag: String = "",
    val languageDialogVisible: Boolean = false,
    val modelChooserStyle: ModelChooserStyle = ModelChooserStyle.Detailed,
    val modelChooserStyleDialogVisible: Boolean = false,
)

sealed interface ThemeSettingsIntent {
    data object Load : ThemeSettingsIntent
    data object OpenLanguageDialog : ThemeSettingsIntent
    data object DismissLanguageDialog : ThemeSettingsIntent
    data class SelectLanguage(val tag: String) : ThemeSettingsIntent
    data object OpenModelChooserStyleDialog : ThemeSettingsIntent
    data object DismissModelChooserStyleDialog : ThemeSettingsIntent
    data class SelectModelChooserStyle(val style: ModelChooserStyle) : ThemeSettingsIntent
}

sealed interface ThemeSettingsEffect {
    data class ApplyApplicationLocales(val languageTag: String) : ThemeSettingsEffect
}

class ThemeSettingsViewModel :
    ComposeMVIViewModel<ThemeSettingsIntent, ThemeSettingsUiState, ThemeSettingsEffect>() {

    override fun initUiState(): ThemeSettingsUiState = ThemeSettingsUiState()

    override suspend fun handleIntent(intent: ThemeSettingsIntent) {
        when (intent) {
            ThemeSettingsIntent.Load -> loadSettings()
            ThemeSettingsIntent.OpenLanguageDialog ->
                updateState { copy(languageDialogVisible = true) }

            ThemeSettingsIntent.DismissLanguageDialog ->
                updateState { copy(languageDialogVisible = false) }

            is ThemeSettingsIntent.SelectLanguage -> selectLanguage(intent.tag)

            ThemeSettingsIntent.OpenModelChooserStyleDialog ->
                updateState { copy(modelChooserStyleDialogVisible = true) }

            ThemeSettingsIntent.DismissModelChooserStyleDialog ->
                updateState { copy(modelChooserStyleDialogVisible = false) }

            is ThemeSettingsIntent.SelectModelChooserStyle -> selectModelChooserStyle(intent.style)
        }
    }

    private suspend fun loadSettings() {
        val tag = XRepo.languageTag()
        val styleKey = XRepo.modelChooserStyle()
        updateState {
            copy(
                languageTag = tag,
                modelChooserStyle = ModelChooserStyle.fromStorageKey(styleKey),
            )
        }
    }

    private suspend fun selectLanguage(tag: String) {
        updateState { copy(languageTag = tag, languageDialogVisible = false) }
        XRepo.setLanguageTag(tag)
        sendEffect(ThemeSettingsEffect.ApplyApplicationLocales(tag))
    }

    private suspend fun selectModelChooserStyle(style: ModelChooserStyle) {
        updateState { copy(modelChooserStyle = style, modelChooserStyleDialogVisible = false) }
        XRepo.setModelChooserStyle(style.storageKey)
    }
}
