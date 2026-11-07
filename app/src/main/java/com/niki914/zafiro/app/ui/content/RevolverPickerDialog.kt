package com.niki914.zafiro.app.ui.content

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.niki914.uikit.dialog.ZafiroDialog
import com.niki914.uikit.dialog.ZafiroDialogButton
import com.niki914.uikit.infra.component.SettingsItemSurface
import com.niki914.uikit.infra.shape.G2FieldShape
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.model.SavedConfigSummary
import kotlin.math.roundToInt

/**
 * 轮值候选管理弹窗（r21 Item 3d；r22 重构）：
 * - 勾选候选加入轮值（实时落盘）；再点已勾选项的 ✅ 即可取消勾选；
 * - 每个已选候选可上移/下移调整轮转顺序；
 * - 每个已选候选可设「每周期请求数」（权重）与「冷却秒数」。
 * 纯视图：所有变更通过回调上抛给 ViewModel，不本地缓存。
 */
@Composable
internal fun RevolverPickerDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    configs: List<SavedConfigSummary>,
    selectedIds: List<String>,
    counts: Map<String, Int>,
    delays: Map<String, Long>,
    onToggle: (configId: String) -> Unit,
    onReorder: (configId: String, offset: Int) -> Unit,
    onCount: (configId: String, value: Int) -> Unit,
    onDelay: (configId: String, value: Long) -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    ZafiroDialog(
        visible = visible,
        onDismissRequest = onDismissRequest,
        title = {
            Text(
                text = stringResource(R.string.ui_settings_revolver_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        text = {
            Text(
                text = stringResource(R.string.ui_settings_revolver_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        content = {
            val highlightShape = remember { G2FieldShape(16.dp) }
            // r22c Bug1：去掉 heightIn(max=420dp) 硬裁剪——勾选多项后两行步进器/冷却被挤出可达区。
            // 改用外层 ZafiroDialog 面板的 verticalScroll 承载整列，所有候选的「次数/冷却」步进器永远可滚动到。
            Column(modifier = Modifier.fillMaxWidth()) {
                if (configs.isEmpty()) {
                    Text(
                        text = stringResource(R.string.ui_settings_revolver_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                    )
                } else {
                    Text(
                        text = stringResource(R.string.ui_settings_revolver_order),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                    )
                    // 列表顺序即轮转顺序：先按已选顺序展示（可上移/下移），再展示未选（可勾选加入）。
                    val ordered = selectedIds.mapNotNull { id -> configs.firstOrNull { it.id == id } }
                    val remaining = configs.filter { it.id !in selectedIds }
                    ordered.forEachIndexed { index, config ->
                        key(config.id) {
                            val position = index + 1
                            SettingsItemSurface(
                                onClick = null,
                                shape = highlightShape,
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                                minHeight = 48.dp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        IconButton(
                                            onClick = { onToggle(config.id) },
                                            modifier = Modifier.size(28.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.CheckCircle,
                                                contentDescription = stringResource(R.string.ui_settings_revolver_remove),
                                                modifier = Modifier.size(20.dp),
                                                tint = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "$position",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.widthIn(min = 22.dp),
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = config.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        IconButton(
                                            onClick = { onReorder(config.id, -1) },
                                            enabled = index > 0,
                                            modifier = Modifier.size(28.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.ArrowUpward,
                                                contentDescription = stringResource(R.string.ui_settings_revolver_reorder_up),
                                                modifier = Modifier.size(18.dp),
                                                // r22c Bug1：高对比色——默认 contentColor 与背景几乎同色看不见。
                                                tint = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                        IconButton(
                                            onClick = { onReorder(config.id, +1) },
                                            enabled = index < ordered.size - 1,
                                            modifier = Modifier.size(28.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.ArrowDownward,
                                                contentDescription = stringResource(R.string.ui_settings_revolver_reorder_down),
                                                modifier = Modifier.size(18.dp),
                                                // r22c Bug1：高对比色。
                                                tint = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    }
                                    // r24 Item 4：计数与冷却各占一整行（标题 + 单位/数值在左，
                                    // 控件在右 / 滑杆在标题行下方铺满），上下堆叠、各自左对齐到卡片内容左缘。
                                    // 计数行不再把单位塞进窄数值框，消除杂散的「c」。
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = "${stringResource(R.string.ui_settings_revolver_count)}（${stringResource(R.string.ui_settings_revolver_count_unit)}）",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        StepperRow(
                                            value = (counts[config.id] ?: 1).toLong(),
                                            onDecrement = { onCount(config.id, (counts[config.id] ?: 1) - 1) },
                                            onIncrement = { onCount(config.id, (counts[config.id] ?: 1) + 1) },
                                        )
                                    }
                                    // r23 Item 1：冷却秒数 0..120s 滑杆（与 IAB 设置里"标签上限"滑杆同款手感）。
                                    // 标题/数值一行，滑杆在其下方铺满全宽（黄线位置）。
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = stringResource(R.string.ui_settings_revolver_delay),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                        )
                                        Text(
                                            text = "${(delays[config.id] ?: 0L)}${stringResource(R.string.ui_settings_revolver_delay_unit)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            maxLines = 1,
                                        )
                                    }
                                    Slider(
                                        value = (delays[config.id] ?: 0L).toFloat()
                                            .coerceIn(0f, 120f),
                                        onValueChange = { newValue ->
                                            onDelay(
                                                config.id,
                                                newValue.roundToInt().coerceIn(0, 120).toLong(),
                                            )
                                        },
                                        valueRange = 0f..120f,
                                        steps = 23, // 0,5,…,120 共 24 个刻度 → steps = 23
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 8.dp, end = 8.dp),
                                    )
                                }
                            }
                        }
                    }
                    if (remaining.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.ui_settings_revolver_select_keys),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 4.dp),
                        )
                    }
                    remaining.forEach { config ->
                        key(config.id) {
                            SettingsItemSurface(
                                onClick = { onToggle(config.id) },
                                shape = highlightShape,
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                                minHeight = 48.dp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.CheckCircle,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = config.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        text = config.modelId,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 140.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        actions = {
            ZafiroDialogButton(
                text = stringResource(R.string.ui_settings_revolver_confirm),
                onClick = { onConfirm(selectedIds) },
                isPrimary = selectedIds.size >= 2,
            )
        },
    )
}

/**
 * 单行步进器：[−] 数值 [＋]。
 * r24 Item 4：数值只展示数字（不带单位）。单位随 label 一起放在外层标题行，
 * 避免「1 reqs/cycle」在窄框里换行溢出、露出杂散的「c」。
 */
@Composable
private fun StepperRow(
    value: Long,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(
            onClick = onDecrement,
            enabled = value > 0,
            modifier = Modifier.size(28.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Remove,
                contentDescription = stringResource(R.string.ui_settings_revolver_stepper_down),
                modifier = Modifier.size(18.dp),
                // r22c Bug1：高对比色——默认 contentColor 与背景同色看不见。
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text = "$value",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 28.dp),
        )
        IconButton(
            onClick = onIncrement,
            modifier = Modifier.size(28.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = stringResource(R.string.ui_settings_revolver_stepper_up),
                modifier = Modifier.size(18.dp),
                // r22c Bug1：高对比色。
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
