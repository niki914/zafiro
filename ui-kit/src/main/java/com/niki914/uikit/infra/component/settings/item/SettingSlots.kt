package com.niki914.uikit.infra.component.settings.item

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 设置项左侧插槽 (Leading Slot)：
 * 拥有最高度量优先级，采用固定尺寸度量，不随标题挤压形变。
 */
sealed interface SettingLeadingSlot {
    data object None : SettingLeadingSlot

    /** 普通无底座矢量图标 (24dp) */
    data class PlainIcon(
        val imageVector: ImageVector,
        val contentDescription: String? = null,
        val tint: Color? = null,
    ) : SettingLeadingSlot

    /** 普通无底座 Painter 图标 (24dp) */
    data class PlainPainter(
        val painter: Painter,
        val contentDescription: String? = null,
        val tint: Color? = null,
    ) : SettingLeadingSlot

    /** 带 56dp 圆形背景的图标 (专供 About 页等大图标展示) */
    data class CircularIcon(
        val painter: Painter? = null,
        val imageVector: ImageVector? = null,
        val contentDescription: String? = null,
        val containerColor: Color? = null,
        val tint: Color? = null,
    ) : SettingLeadingSlot

    /** 单选/多选状态选择器 (专供 SavedConfig 等卡片激活切换，尺寸 20dp) */
    data class Selector(
        val selected: Boolean,
        val onSelect: (() -> Unit)? = null,
    ) : SettingLeadingSlot

    /** 自定义左侧插槽内容 */
    data class Custom(val content: @Composable () -> Unit) : SettingLeadingSlot
}

/**
 * 设置项右侧插槽 (Trailing Slot)：
 * 拥有最高度量优先级，且必须与左侧/中间内容保持绝对垂直居中。
 */
sealed interface SettingTrailingSlot {
    data object None : SettingTrailingSlot

    /** 独立箭头 (支持 0° 指向右与 90° 指向下) */
    data class Chevron(
        val rotationZ: Float = 0f,
        val tint: Color? = null,
    ) : SettingTrailingSlot

    /**
     * 状态预览值 + 可选箭头：
     * 内部强制遵循 [SettingsTokens.MaxPreviewWidthRatio] (20%) 刚性限制，严禁侵蚀标题空间。
     * [lockWidth]: 为 true 时刚性锁定槽位宽度为 20%，杜绝 Slider 等数值动态变化时中间标题/副标题抖动换行。
     * 默认 null 时由底座自适应（Expandable Slider 场景自动锁死 20%，常规静态场景自适应弹性收缩）。
     */
    data class Preview(
        val text: String,
        val showChevron: Boolean = true,
        val chevronRotationZ: Float = 0f,
        val textColor: Color? = null,
        val lockWidth: Boolean? = null,
    ) : SettingTrailingSlot

    /**
     * 行级开关 (51dp x 31dp)。
     * [onCheckedChange] 为 null 时，默认随全卡片统一的 onClick 翻转，零单独手势拦截。
     */
    data class Toggle(
        val checked: Boolean,
        val enabled: Boolean = true,
        val onCheckedChange: ((Boolean) -> Unit)? = null,
    ) : SettingTrailingSlot

    /** 次级动作文字 (如「编辑」) + 可选箭头 */
    data class ActionText(
        val text: String,
        val showChevron: Boolean = false,
        val onClick: (() -> Unit)? = null,
    ) : SettingTrailingSlot

    /** 自定义右侧插槽内容 */
    data class Custom(val content: @Composable RowScope.() -> Unit) : SettingTrailingSlot
}

/**
 * 设置项下方可展开插槽 (Expandable Slot)：
 * 作为 Header Row 下方独立解耦的扩展内容，折叠时不占高度，展开时不干扰头部度量缓存。
 */
sealed interface SettingExpandableSlot {
    data object None : SettingExpandableSlot

    /** 通用滑块展开项 (支持无极与有极刻度点吸附) */
    data class Slider(
        val value: Float,
        val valueRange: ClosedFloatingPointRange<Float>,
        val steps: Int = 0,
        val startLabel: String? = null,
        val endLabel: String? = null,
        val enabled: Boolean = true,
        val onValueChange: (Float) -> Unit,
        val onValueCommit: ((Float) -> Unit)? = null,
        val onValueChangeFinished: (() -> Unit)? = null,
    ) : SettingExpandableSlot

    /** 自定义展开体内容 (如多行输入框等) */
    data class Custom(val content: @Composable ColumnScope.() -> Unit) : SettingExpandableSlot
}
