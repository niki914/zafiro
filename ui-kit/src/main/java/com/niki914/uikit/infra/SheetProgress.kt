package com.niki914.uikit.infra

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * modal sheet 的展开程度，供页面侧把「背景虚化 + 蒙版」和 sheet 的开合绑在一起。
 *
 * sheet 跑在独立窗口里，窗口内的 Modifier.blur / Backdrop 采不到页面内容，虚化只能由页面侧
 * （[LiquidScreen] 的 Layer 1~3）执行；蒙版同理：M3 自带的 scrim 跟的是「最近锚点」这种阶跃量，
 * 下拉过半就整块消失，而这里传的是「sheet 已被遮住的像素高度 / sheet 自身高度」，拖拽全程跟手、
 * 离屏才归零，正是蒙版该跟的时钟。
 *
 * 页面没提供（默认 null）时，[com.niki914.uikit.infra.component.OptionSheet] 沿用 M3 自带 scrim。
 */
internal class SheetProgressState {
    /** 归一化展开程度：1 = 完全展开，0 = 收起/离屏。 */
    var progress by mutableFloatStateOf(0f)
        private set

    fun update(progress: Float) {
        this.progress = progress.coerceIn(0f, 1f)
    }
}

internal val LocalSheetProgress: ProvidableCompositionLocal<SheetProgressState?> =
    staticCompositionLocalOf { null }
