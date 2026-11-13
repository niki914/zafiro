package com.niki914.uikit.infra

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * modal sheet 的展开程度，供页面侧把「背景虚化」和 sheet 的开合绑在一起。
 *
 * sheet 跑在独立窗口里，窗口内的 Modifier.blur / Backdrop 采不到页面内容，虚化只能由页面侧
 * （[LiquidScreen] 的 Layer 1~3）执行；这里把 sheet 已被遮住的像素高度按自身高度归一化传过去。
 */
internal class SheetBackdropBlurState {
    /** 归一化展开程度：1 = 完全展开，0 = 收起。 */
    var progress by mutableFloatStateOf(0f)
        private set

    fun update(progress: Float) {
        this.progress = progress.coerceIn(0f, 1f)
    }
}

internal val LocalSheetBackdropBlur: ProvidableCompositionLocal<SheetBackdropBlurState> =
    staticCompositionLocalOf { SheetBackdropBlurState() }
