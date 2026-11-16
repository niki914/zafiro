package com.niki914.uikit.screen

/**
 * 顶栏底边的加载线。不画、有进度、无进度只居其一。
 */
sealed interface TopBarLoad {
    data object None : TopBarLoad

    data object Indeterminate : TopBarLoad

    @ConsistentCopyVisibility
    data class Determinate internal constructor(val fraction: Float) : TopBarLoad

    companion object {
        /** 非数字当 0，其余收进 0..1。 */
        fun determinate(fraction: Float): Determinate {
            val safe = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0f
            return Determinate(safe)
        }
    }
}
