package com.niki914.zafiro.app.ui.model

import androidx.lifecycle.viewModelScope
import com.niki914.uikit.base.ComposeMVIViewModel
import com.niki914.zafiro.repo.XRepo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Markdown 排版缩放允许的范围（百分比与浮点比率），UI 与 VM 共用。 */
const val MinScalePercent = 60
const val MaxScalePercent = 140
const val MinScaleRatio = 0.6f
const val MaxScaleRatio = 1.4f

data class MarkdownScaleUiState(
    val scale: Float = 1.0f,
    val previewScale: Float = scale,
) {
    val scalePercent: Int get() = (scale * 100).roundToInt()
    val isDefault: Boolean get() = (scale * 100).roundToInt() == 100
}

sealed interface MarkdownScaleIntent {
    data object Load : MarkdownScaleIntent
    data class ChangeScale(val scale: Float) : MarkdownScaleIntent
    data class CommitScale(val scale: Float? = null) : MarkdownScaleIntent
}

class MarkdownScaleViewModel :
    ComposeMVIViewModel<MarkdownScaleIntent, MarkdownScaleUiState, Nothing>() {

    private var lastPreviewEmitTime = 0L
    private var pendingPreviewJob: Job? = null
    private var pendingPreviewScale: Float? = null
    private val throttleIntervalMs = 80L

    override fun initUiState(): MarkdownScaleUiState {
        val current = XRepo.markdownScaleSetting.value.coerceIn(MinScaleRatio, MaxScaleRatio)
        return MarkdownScaleUiState(scale = current, previewScale = current)
    }

    override suspend fun handleIntent(intent: MarkdownScaleIntent) {
        when (intent) {
            MarkdownScaleIntent.Load -> loadScale()
            is MarkdownScaleIntent.ChangeScale -> onScaleChanged(intent.scale)
            is MarkdownScaleIntent.CommitScale -> commitScale(intent.scale)
        }
    }

    private suspend fun loadScale() {
        val currentScale = XRepo.markdownScale().coerceIn(MinScaleRatio, MaxScaleRatio)
        updateState { copy(scale = currentScale, previewScale = currentScale) }
    }

    private fun onScaleChanged(newScale: Float) {
        val target = newScale.coerceIn(MinScaleRatio, MaxScaleRatio)
        updateState { copy(scale = target) }
        pendingPreviewScale = target

        val now = System.currentTimeMillis()
        val elapsed = now - lastPreviewEmitTime
        if (elapsed >= throttleIntervalMs) {
            pendingPreviewJob?.cancel()
            pendingPreviewJob = null
            lastPreviewEmitTime = now
            updateState { copy(previewScale = target) }
            pendingPreviewScale = null
        } else {
            if (pendingPreviewJob == null) {
                pendingPreviewJob = viewModelScope.launch {
                    delay(throttleIntervalMs - elapsed)
                    pendingPreviewScale?.let { latest ->
                        lastPreviewEmitTime = System.currentTimeMillis()
                        updateState { copy(previewScale = latest) }
                        pendingPreviewScale = null
                    }
                    pendingPreviewJob = null
                }
            }
        }
    }

    private suspend fun commitScale(scale: Float?) {
        pendingPreviewJob?.cancel()
        pendingPreviewJob = null
        pendingPreviewScale = null
        val target = (scale ?: currentState.scale).coerceIn(MinScaleRatio, MaxScaleRatio)
        lastPreviewEmitTime = System.currentTimeMillis()
        updateState { copy(scale = target, previewScale = target) }
        XRepo.setMarkdownScale(target)
    }
}
