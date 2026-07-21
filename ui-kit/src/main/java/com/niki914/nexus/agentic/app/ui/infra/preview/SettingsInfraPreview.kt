package com.niki914.nexus.agentic.app.ui.infra.preview

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.niki914.nexus.agentic.app.ui.infra.component.SettingExpandableTextCard
import com.niki914.nexus.agentic.app.ui.infra.component.SettingNavigationItem
import com.niki914.nexus.agentic.app.ui.infra.component.SettingToggleItem
import com.niki914.nexus.agentic.app.ui.infra.component.SettingsGroupCard
import com.niki914.nexus.agentic.app.ui.infra.component.SettingsItemDivider
import com.niki914.nexus.agentic.app.ui.infra.component.SettingsListItem
import com.niki914.nexus.base.BaseTheme

@Composable
private fun SettingsInfraPreviewContent() {
    Column(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            text = "Settings Infra Preview",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )

        SettingsGroupCard(title = "基础组内 Item") {
            SettingNavigationItem(
                title = "模型提供商",
                summary = "管理当前可用的模型提供商、默认端点和模型选择入口。",
                currentState = "DeepSeek",
                onClick = {},
            )
            SettingsItemDivider()
            SettingToggleItem(
                title = "启用工具调用",
                description = "允许模型在回答过程中调用已启用的本地工具与 MCP 工具。",
                checked = true,
                onCheckedChange = {},
            )
        }

        SettingsGroupCard(title = "基础列表项样例") {
            SettingsListItem(
                title = "带副标题的基础项",
                summary = "用于确认基础列表项在双行文案下的间距与样式。",
                onClick = {},
            )
            SettingsItemDivider()
            SettingsListItem(
                title = "仅标题基础项",
                onClick = {},
            )
            SettingsItemDivider()
            SettingsListItem(
                title = "带 leading 的基础项",
                summary = "左侧图标通过 leadingContent 注入。",
                leadingContent = {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onClick = {},
            )
            SettingsItemDivider()
            SettingsListItem(
                title = "带状态值的基础项",
                currentState = "v1.0.0",
                leadingContent = {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onClick = {},
            )
        }

        SettingsGroupCard(title = "长文案与 Disabled") {
            SettingNavigationItem(
                title = "这是一个很长很长的导航标题，用来确认标题最多显示两行，同时左侧标题区不会无限挤占整行宽度",
                summary = "这里是一段更长的摘要文案，用来验证副标题同样最多显示两行，并且当右侧状态文案很长时，优先保证左侧标题区稳定展示。",
                currentState = "已选择一个特别长的当前状态值",
                enabled = false,
                onClick = {},
            )
            SettingsItemDivider()
            SettingToggleItem(
                title = "同步远程配置并在需要时自动回退到本地缓存",
                description = "这是一个很长的说明文案，用来验证开关项的标题和副标题都被限制在两行以内，同时观察整行点击和透明 item 语义保持不变。",
                checked = false,
                enabled = false,
                onCheckedChange = {},
            )
            SettingsItemDivider()
            SettingToggleItem(
                title = "仅标题开关项",
                description = null,
                checked = true,
                onCheckedChange = {},
            )
        }

        SettingExpandableTextCard(
            title = "折叠态可展开卡片，标题最多显示两行并保留常驻箭头",
            description = "折叠态不再展示 preview，只保留标题和右侧箭头，同时说明文案仍然最多显示两行。",
            value = "https://api.example.com/v1/chat/completions",
            onValueChange = {},
            placeholder = "请输入地址",
            expanded = false,
        )

        SettingExpandableTextCard(
            title = "展开态可展开卡片，箭头会随展开状态旋转",
            description = "展开后展示输入区域，说明文案仍然保持两行以内，避免和输入内容争抢层级。",
            value = "第一行内容\n第二行内容\n第三行内容",
            onValueChange = {},
            placeholder = "请输入多行内容",
            expanded = true,
        )

        SettingExpandableTextCard(
            title = "Disabled 可展开卡片",
            description = "用于确认 disabled 下的卡片底色、标题和辅助文案透明度都能稳定显示。",
            value = "已存在但不可编辑的内容",
            onValueChange = {},
            placeholder = "不可编辑",
            enabled = false,
            expanded = false,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Preview(name = "Settings Infra Light", showBackground = true, widthDp = 420, heightDp = 1200)
@Composable
private fun SettingsInfraLightPreview() {
    BaseTheme(darkTheme = false, dynamicColor = false) {
        Surface {
            SettingsInfraPreviewContent()
        }
    }
}

@Preview(
    name = "Settings Infra Dark",
    showBackground = true,
    widthDp = 420,
    heightDp = 1200,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun SettingsInfraDarkPreview() {
    BaseTheme(darkTheme = true, dynamicColor = false) {
        Surface {
            SettingsInfraPreviewContent()
        }
    }
}
