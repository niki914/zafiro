package com.niki914.zafiro.app.ui.model

import com.niki914.uikit.base.ComposeMVIViewModel
import com.niki914.zafiro.repo.XRepo
import kotlin.math.roundToInt

/** Markdown 排版缩放允许的范围（百分比），UI 与 VM 共用。 */
const val MinScalePercent = 60
const val MaxScalePercent = 140

data class MarkdownScaleUiState(
    val scalePercent: Int = 100,
) {
    val scale: Float get() = scalePercent / 100f
    val isDefault: Boolean get() = scalePercent == 100
}

sealed interface MarkdownScaleIntent {
    data object Load : MarkdownScaleIntent
    data class ChangeScale(val percent: Int) : MarkdownScaleIntent
    data class CommitScale(val percent: Int? = null) : MarkdownScaleIntent
}

class MarkdownScaleViewModel :
    ComposeMVIViewModel<MarkdownScaleIntent, MarkdownScaleUiState, Nothing>() {

    override fun initUiState(): MarkdownScaleUiState {
        val percent = (XRepo.markdownScaleSetting.value * 100).roundToInt()
            .coerceIn(MinScalePercent, MaxScalePercent)
        return MarkdownScaleUiState(scalePercent = percent)
    }

    override suspend fun handleIntent(intent: MarkdownScaleIntent) {
        when (intent) {
            MarkdownScaleIntent.Load -> loadScale()
            is MarkdownScaleIntent.ChangeScale -> updateState { copy(scalePercent = intent.percent) }
            is MarkdownScaleIntent.CommitScale -> commitScale(intent.percent)
        }
    }

    private suspend fun loadScale() {
        val currentScale = XRepo.markdownScale()
        val percent = (currentScale * 100).roundToInt().coerceIn(MinScalePercent, MaxScalePercent)
        updateState { copy(scalePercent = percent) }
    }

    private suspend fun commitScale(percent: Int?) {
        val target = percent ?: currentState.scalePercent
        val clamped = target.coerceIn(MinScalePercent, MaxScalePercent)
        updateState { copy(scalePercent = clamped) }
        XRepo.setMarkdownScale(clamped / 100f)
    }
}
