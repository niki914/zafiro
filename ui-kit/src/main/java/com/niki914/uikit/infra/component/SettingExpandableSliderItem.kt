package com.niki914.uikit.infra.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import com.niki914.uikit.infra.component.settings.item.SettingCardItem
import com.niki914.uikit.infra.component.settings.item.SettingExpandableSlot
import com.niki914.uikit.infra.component.settings.item.SettingTrailingSlot

/**
 * 通用可折叠设置滑块项（门面组件，基于统一 [SettingCardItem] 底座）：
 * - 贯彻严格 Slot 优先级：Trailing Action 箭头最高刚性优先级，绝不受预览文本长度影响或挤压；
 * - 预览文本严格 clamp 在 20% 范围内，End-to-End 右对齐向左生长；
 * - 展开区滑块轨道与两端极值文本（Default / Max）绝对垂直居中对齐；
 * - [steps] == 0 时为无极滑动；[steps] > 0 时为有极滑动，带刻度吸附与触觉反馈。
 */
@Composable
fun SettingExpandableSliderItem(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    summary: String? = null,
    valueLabel: String? = null,
    startLabel: String? = null,
    endLabel: String? = null,
    steps: Int = 0,
    enabled: Boolean = true,
    initiallyExpanded: Boolean = false,
    expanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    onValueCommit: ((Float) -> Unit)? = null,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val focusManager = LocalFocusManager.current
    var internalExpanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    val isExpanded = expanded ?: internalExpanded

    fun updateExpanded(newVal: Boolean) {
        if (newVal) {
            focusManager.clearFocus()
        }
        if (expanded == null) {
            internalExpanded = newVal
        }
        onExpandedChange?.invoke(newVal)
    }

    SettingCardItem(
        title = title,
        summary = summary,
        modifier = modifier,
        trailingSlot = SettingTrailingSlot.Preview(
            text = valueLabel ?: "${(value * 100).toInt()}%",
            showChevron = true,
            lockWidth = true,
        ),
        expandableSlot = SettingExpandableSlot.Slider(
            value = value,
            valueRange = valueRange,
            steps = steps,
            startLabel = startLabel,
            endLabel = endLabel,
            enabled = enabled,
            onValueChange = onValueChange,
            onValueCommit = onValueCommit,
            onValueChangeFinished = onValueChangeFinished,
        ),
        isExpanded = isExpanded,
        onExpandedChange = ::updateExpanded,
        enabled = enabled,
    )
}
