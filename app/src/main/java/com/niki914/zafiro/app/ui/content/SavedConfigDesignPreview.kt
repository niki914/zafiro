package com.niki914.zafiro.app.ui.content

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.niki914.uikit.base.BaseTheme
import com.niki914.uikit.infra.LiquidDialog
import com.niki914.uikit.infra.LiquidScreen
import com.niki914.uikit.infra.component.MaterialTintLiquidButton
import com.niki914.uikit.infra.component.SettingExpandableTextItem
import com.niki914.uikit.infra.component.SettingToggleItem
import com.niki914.uikit.infra.component.SettingsGroupCard
import com.niki914.uikit.infra.component.SettingsItemDivider
import com.niki914.uikit.infra.rememberLiquidScreenState

// ══════════════════════════════════════════════════════════════════════
// 设计稿 preview（仅预览，方案敲定后拆进正式实现）：
//   1. ModelConfigRedesignPreview      —— 模型配置页改版全貌
//        Access 块（Endpoint / Model / Protocol★ / API Key）
//        + Saved Configuration 列表块（激活态 ◉、滑动删除）
//        + System Prompt 全局块（从配置表单中拆出）
//   2. GeneralSettingsRedesignPreview  —— 设置页新增分组（About 同款样式）
//        Language Value Row★ / 启动时载入上次对话 toggle（默认关）
//   3. SingleChoiceDialogPreview       —— 协议/语言共用选择弹窗
//        面板内部自带 verticalScroll + heightIn 封顶（超长列表内部滚动，
//        弹窗不会撑破），按钮只保留一个「取消」
// 右上角入口：模型配置页 Refresh → Add（由 PageChrome rightAction 承担，
// preview 里不渲染顶栏）。
// ══════════════════════════════════════════════════════════════════════

private val MOCK_PROTOCOLS = listOf(
    "deepseek",
    "openai-chat-completions",
    "openai-responses",
    "anthropic-messages",
)

/** 目标组件：ui-kit 的 SettingValueItem（version/protocol/language 三处共用）。 */
@Composable
private fun DesignValueRow(
    title: String,
    value: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
    }
}

/**
 * 目标组件：Saved Configuration 行（preview 手绘版）。
 * ◉ = 当前生效配置；点击状态圆点 = 设为生效；行其余区域点击 = 加载进上方表单编辑。
 * 正式实现：整行包进 SwipeDismissSettingsItemCard（与记忆页同款左滑删除），
 * 该组件无前置图标槽位——◉ 状态届时改由标题侧徽标（如 "In use"）或 title 前缀表达；
 * 长按进入编辑态等交互到实现期再定。此处手绘以示意布局与激活视觉。
 */
@Composable
private fun DesignSavedConfigRow(
    title: String,
    modelId: String,
    isActive: Boolean,
    onActivateClick: () -> Unit,
    onEditClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEditClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = if (isActive) {
                Icons.Default.RadioButtonChecked
            } else {
                Icons.Default.RadioButtonUnchecked
            },
            contentDescription = null,
            modifier = Modifier
                .size(20.dp)
                .clickable(onClick = onActivateClick),
            tint = if (isActive) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
            },
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = modelId,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Preview(name = "Model Config Redesign", showBackground = true, widthDp = 420, heightDp = 1000)
@Composable
private fun ModelConfigRedesignPreview() {
    BaseTheme {
        var overrideEnabled by remember { mutableStateOf(false) }
        var endpointOpen by remember { mutableStateOf(false) }
        var modelOpen by remember { mutableStateOf(false) }
        var apiKeyOpen by remember { mutableStateOf(false) }
        var apiKeyVisible by remember { mutableStateOf(false) }
        var promptOpen by remember { mutableStateOf(true) }
        var protocol by remember { mutableStateOf("openai-responses") }
        var configsActiveIndex by remember { mutableStateOf(0) }
        var configs by remember {
            mutableStateOf(
                listOf(
                    "DeepSeek 主力" to "deepseek-v4-pro",
                    "Backup OpenAI" to "gpt-5.4",
                    "Anthropic 备用" to "claude-sonnet-4-6",
                )
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // ── Access：正在编辑的那份配置（新建时为空白草稿态） ──
            // 焦点处理沿用现封装（expandedField + onBackgroundTap 清焦），无需额外管理
            SettingsGroupCard {
                SettingExpandableTextItem(
                    title = "Name",
                    value = "Backup OpenAI",
                    onValueChange = {},
                    placeholder = "",
                    minLines = 1,
                    maxLines = 1,
                    expanded = false,
                    onExpandedChange = {},
                )
                SettingsItemDivider()
                SettingToggleItem(
                    title = "Override endpoint",
                    description = if (overrideEnabled) {
                        "Use custom endpoint"
                    } else {
                        "Use provider official endpoint"
                    },
                    checked = overrideEnabled,
                    onCheckedChange = { overrideEnabled = it },
                )
                SettingsItemDivider()
                SettingExpandableTextItem(
                    title = "Endpoint",
                    value = "https://api.openai.com/v1/responses",
                    onValueChange = {},
                    placeholder = "https://",
                    enabled = overrideEnabled,
                    minLines = 3,
                    maxLines = 6,
                    expanded = endpointOpen,
                    onExpandedChange = { endpointOpen = it },
                )
                SettingsItemDivider()
                SettingExpandableTextItem(
                    title = "Model",
                    value = "gpt-5.4",
                    onValueChange = {},
                    placeholder = "",
                    minLines = 1,
                    maxLines = 1,
                    expanded = modelOpen,
                    onExpandedChange = { modelOpen = it },
                )
                SettingsItemDivider()
                DesignValueRow(
                    title = "Protocol",
                    value = protocol,
                    onClick = {},
                )
                SettingsItemDivider()
                SettingExpandableTextItem(
                    title = "API Key",
                    value = "sk-demo-0123456789abcdef",
                    onValueChange = {},
                    placeholder = "",
                    secretVisible = apiKeyVisible,
                    onToggleSecretVisibility = { apiKeyVisible = !apiKeyVisible },
                    minLines = 1,
                    maxLines = 1,
                    expanded = apiKeyOpen,
                    onExpandedChange = { apiKeyOpen = it },
                )
            }

            // ── Saved Configuration：多份保存的接入配置 ──
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Saved Configuration",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                )
                SettingsGroupCard {
                    configs.forEachIndexed { index, (name, modelId) ->
                        if (index > 0) SettingsItemDivider()
                        DesignSavedConfigRow(
                            title = name,
                            modelId = modelId,
                            isActive = index == configsActiveIndex,
                            onActivateClick = { configsActiveIndex = index },
                            onEditClick = {},
                        )
                    }
                }
            }

            // ── System Prompt：全局一份，不随 saved config 走 ──
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "System Prompt",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                )
                SettingsGroupCard {
                    SettingExpandableTextItem(
                        title = "Prompt",
                        value = "You are Zafiro, a helpful assistant.",
                        onValueChange = {},
                        placeholder = "",
                        description = null,
                        minLines = 3,
                        maxLines = 8,
                        expanded = promptOpen,
                        onExpandedChange = { promptOpen = it },
                    )
                }
            }
        }
    }
}

@Preview(name = "General Settings Redesign", showBackground = true, widthDp = 420, heightDp = 400)
@Composable
private fun GeneralSettingsRedesignPreview() {
    BaseTheme {
        var loadLastConversation by remember { mutableStateOf(false) }
        // 语言四选项（跟随系统 / 简体中文 / 繁體中文 / English）同协议弹窗形态，不另设 preview

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SettingsGroupCard {
                DesignValueRow(
                    title = "Language",
                    value = "简体中文",
                    onClick = {},
                )
                SettingsItemDivider()
                SettingToggleItem(
                    title = "启动时载入上次对话",
                    checked = loadLastConversation,
                    onCheckedChange = { loadLastConversation = it },
                )
            }
        }
    }
}

/**
 * 选择弹窗：协议/语言共用一个组件。要点：
 * - 列表直接放 LiquidDialog content 内（面板自带 verticalScroll + 高度上限，
 *   不要自己再包 scroll 层）；
 * - actions 只有一个「取消」，选中项即点即应用即关闭；
 * - 弹窗必须在 LiquidScreen 组合树内挂载（经 host portal 渲染遮罩才盖满全屏）。
 */
@Preview(name = "Single Choice Dialog", showBackground = true, widthDp = 420, heightDp = 800)
@Composable
private fun SingleChoiceDialogPreview() {
    BaseTheme {
        val screenState = rememberLiquidScreenState(
            title = "",
            showLeftButton = false,
            showRightButton = false,
            showBlurLayer = false,
        )
        var selectedProtocol by remember { mutableStateOf("openai-responses") }
        LiquidScreen(state = screenState) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
            ) {
                LiquidDialog(
                    visible = true,
                    onDismissRequest = {},
                    title = {
                        Text(
                            text = "Protocol",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                    content = {
                        MOCK_PROTOCOLS.forEach { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedProtocol = item }
                                    .padding(horizontal = 2.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(
                                    imageVector = if (item == selectedProtocol) {
                                        Icons.Default.RadioButtonChecked
                                    } else {
                                        Icons.Default.RadioButtonUnchecked
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = if (item == selectedProtocol) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                                    },
                                )
                                Text(
                                    text = item,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    },
                    actions = {
                        MaterialTintLiquidButton(
                            text = "Cancel",
                            onClick = {},
                            modifier = Modifier.fillMaxWidth(),
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        )
                    },
                )
            }
        }
    }
}
