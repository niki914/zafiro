package com.niki914.zafiro.app.ui.model

import androidx.annotation.StringRes
import com.niki914.logging.Logger
import com.niki914.uikit.base.ComposeMVIViewModel
import com.niki914.zafiro.app.R
import com.niki914.zafiro.repo.XRepo
import kotlinx.coroutines.CancellationException

/**
 * System Prompt 整页编辑（MVI）。
 *
 * Prompt 为全局一份的行为层配置，独立于 Saved Configuration 持久化
 * （[XRepo.llmConfigs] 的 prompt()/savePrompt()）。卡片点击跳入本页，
 * 整页编辑 + 显式保存，替代原列表页内联展开 + 防抖自动保存。
 */
data class PromptEditUiState(
    /** 编辑中内容。 */
    val content: String = "",
    /** 进入页面时的内容，用于未保存变更判断。 */
    val initialContent: String = "",
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    /** 读失败时展示的内联错误；null = 正常。 */
    val loadError: Boolean = false,
)

val PromptEditUiState.hasUnsavedChanges: Boolean
    get() = !isLoading && content != initialContent

sealed interface PromptEditIntent {
    data object Load : PromptEditIntent
    data class ContentChanged(val value: String) : PromptEditIntent
    data object Save : PromptEditIntent
}

sealed interface PromptEditEffect {
    data object Exit : PromptEditEffect
}

class PromptEditViewModel : ComposeMVIViewModel<
        PromptEditIntent,
        PromptEditUiState,
        PromptEditEffect,
        >() {

    private companion object {
        private const val LOG_TAG = "niki914_nexus_PromptEditViewModel"
    }

    override fun initUiState(): PromptEditUiState = PromptEditUiState()

    override suspend fun handleIntent(intent: PromptEditIntent) {
        when (intent) {
            PromptEditIntent.Load -> load()
            is PromptEditIntent.ContentChanged -> updateState {
                copy(content = intent.value, loadError = false)
            }

            PromptEditIntent.Save -> save()
        }
    }

    private suspend fun load() {
        updateState { copy(isLoading = true, loadError = false) }
        try {
            val prompt = XRepo.llmConfigs.prompt()
            Logger.d(LOG_TAG, "load succeeded length=${prompt.length}")
            updateState {
                copy(
                    content = prompt,
                    initialContent = prompt,
                    isLoading = false,
                    loadError = false,
                )
            }
            // prompt 不存在视为空内容可编辑，不报错；读 store 失败才视为 loadError
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            Logger.w(LOG_TAG, "load failed reason=${throwable.message}")
            updateState { copy(isLoading = false, loadError = true) }
        }
    }

    private suspend fun save() {
        if (currentState.isSaving) return
        updateState { copy(isSaving = true) }
        try {
            XRepo.llmConfigs.savePrompt(currentState.content)
            Logger.i(LOG_TAG, "save succeeded length=${currentState.content.length}")
            updateState {
                copy(
                    initialContent = currentState.content,
                    isSaving = false,
                )
            }
            sendEffect(PromptEditEffect.Exit)
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            Logger.w(LOG_TAG, "save failed reason=${throwable.message}")
            updateState { copy(isSaving = false) }
        }
    }
}
