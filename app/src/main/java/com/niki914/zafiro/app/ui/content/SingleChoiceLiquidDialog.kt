package com.niki914.zafiro.app.ui.content

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.niki914.zafiro.app.R
import com.niki914.uikit.infra.LiquidDialog
import com.niki914.uikit.infra.component.MaterialTintLiquidButton

/**
 * 单选弹窗（Protocol / Language 共用）。
 * - 列表直接放 LiquidDialog content（面板自带 verticalScroll + 高度上限，
 *   超长选项内部滚动，不要自己再包 scroll 层）；
 * - 选中即应用即关闭；actions 只有一个「取消」；
 * - 必须在 LiquidScreen 组合树内挂载（经 host portal 渲染遮罩）。
 */
@Composable
fun <T> SingleChoiceLiquidDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    title: String,
    options: List<T>,
    selectedId: String?,
    optionId: (T) -> String,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
) {
    LiquidDialog(
        visible = visible,
        onDismissRequest = onDismissRequest,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        content = {
            options.forEach { option ->
                val isSelected = optionId(option) == selectedId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(option) }
                        .padding(horizontal = 2.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = if (isSelected) {
                            Icons.Default.RadioButtonChecked
                        } else {
                            Icons.Default.RadioButtonUnchecked
                        },
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                        },
                    )
                    Text(
                        text = optionLabel(option),
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
                text = stringResource(R.string.dialog_cancel),
                onClick = onDismissRequest,
                modifier = Modifier.fillMaxWidth(),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurface,
            )
        },
    )
}
