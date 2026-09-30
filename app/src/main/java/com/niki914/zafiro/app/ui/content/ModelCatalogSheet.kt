package com.niki914.zafiro.app.ui.content

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.niki914.uikit.infra.component.OptionRow
import com.niki914.uikit.infra.component.OptionSheet
import com.niki914.uikit.infra.component.LiquidTextField
import com.niki914.zafiro.app.R

/**
 * 模型目录选择单（id-only）。
 * - 搜索框（header 槽）+ 名单；点一行即选中回填并收起；背景点击关闭；
 * - 选中态：单内字符串 == 输入框 trim。
 */
@Composable
fun ModelCatalogSheet(
    visible: Boolean,
    catalog: List<String>,
    currentModelInput: String,
    onDismissRequest: () -> Unit,
    onSelect: (String) -> Unit,
) {
    // 提前返回是有意的：query 随组合一起销毁，关单再开时搜索框是干净的（原行为）。
    if (!visible) return
    var query by rememberSaveable { mutableStateOf("") }
    val trimmedCurrent = currentModelInput.trim()
    val filtered = rememberFilteredCatalog(catalog, query)
    val screenH = LocalConfiguration.current.screenHeightDp.dp

    OptionSheet(
        visible = visible,
        onDismissRequest = onDismissRequest,
        title = stringResource(R.string.ui_onboard_configure_model_catalog_title),
        // 单高锁在屏高 50%~60%：内容不足撑到 50%，超长列表封顶 60% 内滚动
        bodyModifier = Modifier.heightIn(min = screenH * 0.5f, max = screenH * 0.6f),
        header = {
            LiquidTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = stringResource(
                    R.string.ui_onboard_configure_model_catalog_search_placeholder
                ),
                singleLine = true,
                maxLines = 1,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
            )
        },
    ) { dismissThen ->
        if (filtered.isEmpty()) {
            Text(
                text = stringResource(R.string.ui_onboard_configure_model_catalog_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
        } else {
            LazyColumn {
                items(filtered, key = { it.hashCode() }) { modelId ->
                    OptionRow(
                        title = modelId,
                        checked = modelId == trimmedCurrent,
                        onClick = { dismissThen { onSelect(modelId) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberFilteredCatalog(
    catalog: List<String>,
    query: String,
): List<String> {
    val q = query.trim().lowercase()
    if (q.isBlank()) return catalog
    return catalog.filter { it.lowercase().contains(q) }
}
