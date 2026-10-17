package com.niki914.uikit.infra.component.settings.item

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 设置项全局设计令牌 (Design Tokens)：
 * 统一所有设置项的边距、约束比例、图标与操作靶心尺寸及排版规范，作为全工程唯一事实来源。
 */
object SettingsTokens {
    // ── 容器与内边距 ──
    val CardCornerRadius: Dp = 28.dp // G2CardShape 默认连续曲率圆角
    val CardPaddingHorizontal: Dp = 16.dp // 卡片容器外部边距
    val CardPaddingVertical: Dp = 8.dp

    // ── 单行内部内边距 (内容区 Content Padding) ──
    // 无图标行的文字起点与有图标行的圆底左缘齐平 (20dp)，竖向视觉对齐
    val ContentPaddingStart: Dp = 20.dp
    val ContentPaddingEnd: Dp = 16.dp
    val ContentPaddingTop: Dp = 16.dp
    val ContentPaddingBottom: Dp = 16.dp

    val ContentPadding: PaddingValues = PaddingValues(
        start = ContentPaddingStart,
        top = ContentPaddingTop,
        end = ContentPaddingEnd,
        bottom = ContentPaddingBottom,
    )

    // ── 尺寸与间距 ──
    val ItemMinHeight: Dp = 0.dp // 纯 wrap_content 自适应，高度由标题/副标题行数与内边距完全决定
    val SlotGutter: Dp = 12.dp // 插槽之间的横向安全间距
    val TextSpacing: Dp = 2.dp // 标题与副标题之间的纵向间距
    val SummaryStartIndent: Dp = 2.dp // 说明文案左侧微缩进，增强层级归属感并打破直切线

    // ── 刚性约束指标 ──
    /** 状态预览 (Preview) 占有效内容显示宽度的最大比例，严格禁止侵蚀标题空间 */
    const val MaxPreviewWidthRatio: Float = 0.20f
    const val MaxTitleLines: Int = 2
    const val MaxSummaryLines: Int = 2
    const val MaxTotalLines: Int = 4

    // ── 图标与操作靶心标准尺寸 ──
    val ChevronSize: Dp = 24.dp // 统一消灭 20dp / 22dp / 24dp 漂移
    val PlainIconSize: Dp = 24.dp
    val CircularIconContainerSize: Dp = 56.dp // About 页圆形底座尺寸
    val CircularIconInnerSize: Dp = 24.dp // About 页圆形底座内图标尺寸
    val SelectorSize: Dp = 20.dp // SavedConfig 单选/多选框尺寸
    val ToggleWidth: Dp = 51.dp // LiquidToggle 标准尺寸
    val ToggleHeight: Dp = 31.dp

    // ── 排版字号 ──
    val TitleFontSize = 18.sp // 统一消灭 17sp 漂移
    val SummaryFontSize = 13.sp
    val SummaryLineHeight = 18.sp
    val PreviewFontSize = 14.sp

    // ── 动效标准 ──
    const val ArrowAnimationDurationMs: Int = 320
    const val ExpandAnimationDurationMs: Int = 320
    const val PressAnimationDurationMs: Int = 500
}
