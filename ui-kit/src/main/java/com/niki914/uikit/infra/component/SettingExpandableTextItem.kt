package com.niki914.uikit.infra.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.niki914.uikit.infra.component.settings.item.SettingCardItem
import com.niki914.uikit.infra.component.settings.item.SettingExpandableSlot
import com.niki914.uikit.infra.component.settings.item.SettingTrailingSlot
import com.niki914.uikit.textfield.ZafiroOutlinedTextField
import com.niki914.uikit.textfield.ZafiroSecretTextField

/**
 * 通用可折叠文本输入设置项（门面组件，基于统一 [SettingCardItem] 底座）：
 * - 贯彻严格 Slot 体系与统一设计令牌 [SettingsTokens]；
 * - 头部标题与箭头完全复用 [SettingCardItem]，消灭 4dp 边距漂移与 22dp/24dp 箭头尺寸漂移；
 * - 展开区输入框无缝对接键盘避让 [LocalSettingsFormKeyboardAvoidance] 与焦点联动。
 */
@Composable
fun SettingExpandableTextItem(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
    initiallyExpanded: Boolean = false,
    expanded: Boolean? = null,
    minLines: Int = 4,
    maxLines: Int = 10,
    secretVisible: Boolean = false,
    onToggleSecretVisibility: (() -> Unit)? = null,
    toggleSecretVisibleContentDescription: String? = null,
    toggleSecretHiddenContentDescription: String? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    /** 展开后输入框行的尾部内容（如模型扫描按钮）；null = 输入框占满。 */
    fieldTrailingContent: (@Composable () -> Unit)? = null,
) {
    var internalExpanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    val isExpanded = expanded ?: internalExpanded
    val focusRequester = remember { FocusRequester() }

    fun updateExpanded(newVal: Boolean) {
        if (expanded == null) {
            internalExpanded = newVal
        }
        onExpandedChange?.invoke(newVal)
    }

    LaunchedEffect(isExpanded, enabled) {
        if (isExpanded && enabled) {
            focusRequester.requestFocus()
        }
    }

    val usesSecretField = onToggleSecretVisibility != null &&
            toggleSecretVisibleContentDescription != null &&
            toggleSecretHiddenContentDescription != null
    val singleLine = maxLines == 1
    val fieldMinHeight = if (singleLine) 52.dp else 136.dp

    // 键盘避让登记：展开并获焦时把真实矩形交给脚手架（只服务表单，不经过公共输入框容器）。
    val keyboardAvoidance = LocalSettingsFormKeyboardAvoidance.current
    val avoidanceKey = remember { Any() }
    var fieldBounds by remember { mutableStateOf<Rect?>(null) }
    var fieldFocused by remember { mutableStateOf(false) }

    LaunchedEffect(keyboardAvoidance, isExpanded, fieldFocused, fieldBounds) {
        val avoidance = keyboardAvoidance ?: return@LaunchedEffect
        if (!isExpanded || !fieldFocused) {
            avoidance.release(avoidanceKey)
        } else {
            avoidance.request(avoidanceKey)
            fieldBounds?.let { avoidance.activeBounds = it }
        }
    }

    SettingCardItem(
        title = title,
        summary = description,
        modifier = modifier,
        enabled = enabled,
        isExpanded = isExpanded,
        onExpandedChange = ::updateExpanded,
        trailingSlot = SettingTrailingSlot.Chevron(),
        expandableSlot = SettingExpandableSlot.Custom {
            @Composable
            fun fieldContent(fieldModifier: Modifier) {
                if (usesSecretField) {
                    ZafiroSecretTextField(
                        value = value,
                        onValueChange = onValueChange,
                        placeholder = placeholder,
                        visible = secretVisible,
                        onToggleVisibility = requireNotNull(onToggleSecretVisibility),
                        toggleVisibleContentDescription = requireNotNull(
                            toggleSecretVisibleContentDescription,
                        ),
                        toggleHiddenContentDescription = requireNotNull(
                            toggleSecretHiddenContentDescription,
                        ),
                        enabled = enabled,
                        singleLine = singleLine,
                        moveCursorToEndOnFocus = true,
                        modifier = fieldModifier
                            .heightIn(min = fieldMinHeight)
                            .onFocusChanged { fieldFocused = it.isFocused }
                            .onGloballyPositioned { coordinates ->
                                fieldBounds = coordinates.takeIf { it.isAttached }?.let {
                                    Rect(it.positionInRoot(), it.size.toSize())
                                }
                            }
                            .focusRequester(focusRequester),
                    )
                } else {
                    ZafiroOutlinedTextField(
                        value = value,
                        onValueChange = onValueChange,
                        placeholder = placeholder,
                        enabled = enabled,
                        singleLine = singleLine,
                        minLines = minLines,
                        maxLines = maxLines,
                        moveCursorToEndOnFocus = true,
                        modifier = fieldModifier
                            .heightIn(min = fieldMinHeight)
                            .onFocusChanged { fieldFocused = it.isFocused }
                            .onGloballyPositioned { coordinates ->
                                fieldBounds = coordinates.takeIf { it.isAttached }?.let {
                                    Rect(it.positionInRoot(), it.size.toSize())
                                }
                            }
                            .focusRequester(focusRequester),
                    )
                }
            }

            val trailing = fieldTrailingContent
            if (trailing != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    fieldContent(Modifier.weight(1f))
                    trailing()
                }
            } else {
                fieldContent(Modifier.fillMaxWidth())
            }
        },
    )
}
