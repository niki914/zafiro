package com.niki914.uikit.infra.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.niki914.uikit.base.LocalThemeStyle
import com.niki914.uikit.base.ThemeStyle
import com.niki914.uikit.infra.component.settings.item.SettingsTokens
import com.niki914.uikit.infra.component.settings.resolveConnectedShape
import com.niki914.uikit.infra.shape.G2CardShape

/**
 * 设置项自适应分组作用域。
 */
interface SettingsConnectedGroupScope {
    fun item(content: @Composable (shape: Shape, isStandalone: Boolean, index: Int) -> Unit)
}

private class SettingsConnectedGroupScopeImpl : SettingsConnectedGroupScope {
    val items = mutableListOf<@Composable (shape: Shape, isStandalone: Boolean, index: Int) -> Unit>()

    override fun item(content: @Composable (shape: Shape, isStandalone: Boolean, index: Int) -> Unit) {
        items.add(content)
    }
}

/**
 * 新一代多模自适应设置项分组组件：
 * - [ThemeStyle.LiquidGlass]: 渲染为单体圆角大卡片，内部各子项透明并自动插入 [SettingsItemDivider]；
 * - [ThemeStyle.Material3] / [ThemeStyle.Material3Expressive]: 渲染为接缝卡片集合 (Connected Shapes)，各项具备独立圆角并在纵向保持 3dp 间距。
 */
@Composable
fun SettingsConnectedGroup(
    modifier: Modifier = Modifier,
    title: String? = null,
    builder: SettingsConnectedGroupScope.() -> Unit,
) {
    val scope = SettingsConnectedGroupScopeImpl().apply(builder)
    val themeStyle = LocalThemeStyle.current
    val isConnected = themeStyle != ThemeStyle.LiquidGlass

    if (isConnected) {
        Column(modifier = modifier.fillMaxWidth()) {
            if (!title.isNullOrBlank()) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    modifier = Modifier.padding(
                        start = SettingsTokens.ContentPaddingStart,
                        end = SettingsTokens.ContentPaddingEnd,
                    ),
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                val total = scope.items.size
                scope.items.forEachIndexed { index, itemContent ->
                    val shape = resolveConnectedShape(index, total)
                    itemContent(shape, true, index)
                }
            }
        }
    } else {
        SettingsGroupCard(
            title = title,
            modifier = modifier,
        ) {
            val total = scope.items.size
            scope.items.forEachIndexed { index, itemContent ->
                itemContent(G2CardShape(SettingsTokens.CardCornerRadius), false, index)
                if (index != total - 1) {
                    SettingsItemDivider()
                }
            }
        }
    }
}

/**
 * 列表驱动的多模自适应设置项分组卡片：
 * 接收集合数据并在 LiquidGlass 与 M3 / Expressive 之间自动切换接缝形态与内部分割线。
 */
@Composable
fun <T> SettingsGroupCard(
    items: List<T>,
    modifier: Modifier = Modifier,
    title: String? = null,
    itemContent: @Composable (item: T, shape: Shape, isStandalone: Boolean, index: Int) -> Unit,
) {
    SettingsConnectedGroup(
        title = title,
        modifier = modifier,
    ) {
        items.forEach { item ->
            item { shape, isStandalone, index ->
                itemContent(item, shape, isStandalone, index)
            }
        }
    }
}

/**
 * iOS 风分组卡片：外置章节标题 + 圆角卡片容器。
 * 排版抄自 ui/settings/StyledToggle 的 independent=true 分支，颜色走 MaterialTheme，
 * 深浅色由 colorScheme.surfaceContainerLow / onSurfaceVariant 自动适配。
 *
 * 卡片本身 clip 成圆角，方便子项（如 `SettingNavigationItem`）在按压渐变时
 * 不会溢出圆角边界。
 */
@Composable
fun SettingsGroupCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val titleColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
    val cardColor = MaterialTheme.colorScheme.surfaceContainerLow
    val cardShape = G2CardShape(28.dp)

    Column(modifier = modifier.fillMaxWidth()) {
        if (!title.isNullOrBlank()) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = titleColor,
                modifier = Modifier.padding(
                    start = SettingsTokens.ContentPaddingStart,
                    end = SettingsTokens.ContentPaddingEnd,
                ),
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(cardShape)
                .background(cardColor, cardShape),
            content = content,
        )
    }
}
