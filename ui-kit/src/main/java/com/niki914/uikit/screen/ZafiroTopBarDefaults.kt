package com.niki914.uikit.screen

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 顶栏布局常量与统一规范。
 *
 * 规范：统一采用 72.dp 按钮插槽高度（48dp 按钮 footprint + 上下各 12dp 边距），
 * 无论 LiquidGlass、MD3 还是 MD3E 均在此高度下同构对齐，消除主题切页抖动。
 */
object ZafiroTopBarDefaults {
    /** 顶栏按钮与标题插槽标准高度（统一 72dp，三模同构） */
    val ButtonSlotHeight: Dp = 72.dp

    /** 顶栏内容左右边距 */
    val HorizontalPadding: Dp = 4.dp

    /** 标题左右默认内边距（避让两侧 48dp 按钮 footprint 与 12dp 边距） */
    val TitleHorizontalPadding: Dp = 72.dp

    /** 展开态内容与屏幕边距（与内容区卡片 16dp 保持垂直齐平） */
    val ExpandedContentPadding: Dp = 16.dp
}
