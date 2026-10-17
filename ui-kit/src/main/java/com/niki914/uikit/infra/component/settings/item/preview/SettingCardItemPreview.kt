package com.niki914.uikit.infra.component.settings.item.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.niki914.uikit.base.BaseTheme
import com.niki914.uikit.infra.component.SettingsGroupCard
import com.niki914.uikit.infra.component.SettingsItemDivider
import com.niki914.uikit.infra.component.settings.item.SettingCardItem
import com.niki914.uikit.infra.component.settings.item.SettingExpandableSlot
import com.niki914.uikit.infra.component.settings.item.SettingLeadingSlot
import com.niki914.uikit.infra.component.settings.item.SettingTrailingSlot

@Composable
private fun SettingCardItemPreviewGallery() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Text(
            text = "SettingCardItem V1 架构全景试水 Preview",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )

        // ── 场景 1: 标准导航与状态值项 (Non-expandable) ──
        SettingsGroupCard(title = "1. 标准导航与状态项 (Non-expandable)") {
            SettingCardItem(
                title = "模型提供商",
                summary = "管理当前可用的模型提供商、默认端点和模型选择入口。",
                trailingSlot = SettingTrailingSlot.Preview(
                    text = "DeepSeek",
                    showChevron = true,
                ),
                onClick = {},
            )
            SettingsItemDivider()
            SettingCardItem(
                title = "仅标题基础项",
                trailingSlot = SettingTrailingSlot.Chevron(),
                onClick = {},
            )
        }

        // ── 场景 2: 行级开关项 (Non-expandable) ──
        SettingsGroupCard(title = "2. 组内行级开关项 (Non-expandable)") {
            var checked by remember { mutableStateOf(true) }
            SettingCardItem(
                title = "启用本地工具调用",
                summary = "允许模型在回答过程中调度已启用的本地 Python 工具与 MCP 服务。",
                trailingSlot = SettingTrailingSlot.Toggle(checked = checked),
                onClick = { checked = !checked },
            )
            SettingsItemDivider()
            var checked2 by remember { mutableStateOf(false) }
            SettingCardItem(
                title = "仅标题开关项",
                trailingSlot = SettingTrailingSlot.Toggle(checked = checked2),
                onClick = { checked2 = !checked2 },
            )
        }

        // ── 场景 3: 可折叠滑块项 (Expandable · 上次提交重点) ──
        SettingsGroupCard(title = "3. 可折叠滑块项 (Expandable · Auto Compaction)") {
            var sliderExpanded by remember { mutableStateOf(true) }
            var sliderValue by remember { mutableFloatStateOf(95f) }

            SettingCardItem(
                title = "Auto compaction threshold",
                summary = "Trigger auto compaction when context history reaches this ratio",
                trailingSlot = SettingTrailingSlot.Preview(
                    text = "${sliderValue.toInt()}%",
                    showChevron = true,
                ),
                isExpanded = sliderExpanded,
                onExpandedChange = { sliderExpanded = it },
                expandableSlot = SettingExpandableSlot.Slider(
                    value = sliderValue,
                    valueRange = 0f..95f,
                    steps = 19,
                    startLabel = "0%",
                    endLabel = "95%",
                    onValueChange = { sliderValue = it },
                ),
            )
        }

        // ── 场景 4: About 页面风格 (56dp 圆形图标插槽) ──
        SettingsGroupCard(title = "4. About 页面风格 (56dp 圆形大图标插槽)") {
            SettingCardItem(
                title = "关于 Zafiro",
                summary = "开源本地优先智能语音助手客户端",
                leadingSlot = SettingLeadingSlot.CircularIcon(
                    imageVector = Icons.Default.Info,
                ),
                trailingSlot = SettingTrailingSlot.Preview(
                    text = "v1.2.0",
                    showChevron = true,
                ),
                onClick = {},
            )
        }

        // ── 场景 5: Saved Configuration 风格 (单选选择器插槽) ──
        SettingsGroupCard(title = "5. Saved Configuration 风格 (单选选择器插槽)") {
            var activeConfig by remember { mutableStateOf(0) }

            SettingCardItem(
                title = "DeepSeek-V3 生产配置",
                summary = "deepseek-chat · https://api.deepseek.com/v1",
                leadingSlot = SettingLeadingSlot.Selector(
                    selected = activeConfig == 0,
                ),
                trailingSlot = SettingTrailingSlot.ActionText(
                    text = "编辑",
                    showChevron = true,
                ),
                onClick = { activeConfig = 0 },
            )
            SettingsItemDivider()
            SettingCardItem(
                title = "Claude-3.5-Sonnet 备用配置",
                summary = "claude-3-5-sonnet-20241022 · 官方代理端点",
                leadingSlot = SettingLeadingSlot.Selector(
                    selected = activeConfig == 1,
                ),
                trailingSlot = SettingTrailingSlot.ActionText(
                    text = "编辑",
                    showChevron = true,
                ),
                onClick = { activeConfig = 1 },
            )
        }

        // ── 场景 6: 极限超长文本压力测试 (20% Preview Clamp & 4行垂直居中) ──
        SettingsGroupCard(title = "6. 极限超长文本压力测试 (Break-Length Stress)") {
            SettingCardItem(
                title = "这是一个非常非常长的设置项标题用来全面测试突破两行显示上限时的排版表现效果",
                summary = "这是一段非常详尽的技术说明文案，深入测试上下文历史截断与智能总结触发时机，同样超出了两行显示的边界",
                leadingSlot = SettingLeadingSlot.PlainIcon(
                    imageVector = Icons.Default.Settings,
                ),
                trailingSlot = SettingTrailingSlot.Preview(
                    text = "https://api.openai.com/v1/chat/completions/very/long/path",
                    showChevron = true,
                ),
                onClick = {},
            )
        }

        // ── 场景 7: 散开独立卡片形态 (CardList 模式) ──
        Text(
            text = "7. 散开独立卡片形态 (CardList 模式，各项自成卡片)",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingCardItem(
                title = "独立记忆项卡片 A",
                summary = "支持未来直接在单卡片上绑定左滑删除手势",
                trailingSlot = SettingTrailingSlot.Chevron(),
                isStandaloneCard = true,
                onClick = {},
            )
            SettingCardItem(
                title = "独立记忆项卡片 B",
                summary = "散开形态与聚拢形态共享同一套底座",
                trailingSlot = SettingTrailingSlot.Chevron(),
                isStandaloneCard = true,
                onClick = {},
            )
        }
    }
}

@Preview(name = "SettingCardItem Light Preview", showBackground = true, widthDp = 420, heightDp = 1800)
@Composable
private fun SettingCardItemLightPreview() {
    BaseTheme(darkTheme = false, dynamicColor = false) {
        Surface {
            SettingCardItemPreviewGallery()
        }
    }
}

@Preview(name = "SettingCardItem Dark Preview", showBackground = true, widthDp = 420, heightDp = 1800)
@Composable
private fun SettingCardItemDarkPreview() {
    BaseTheme(darkTheme = true, dynamicColor = false) {
        Surface {
            SettingCardItemPreviewGallery()
        }
    }
}
