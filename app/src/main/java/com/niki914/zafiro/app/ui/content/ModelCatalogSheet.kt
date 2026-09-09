package com.niki914.zafiro.app.ui.content

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.niki914.uikit.infra.component.LiquidTextField
import com.niki914.uikit.infra.component.SettingsItemSurface
import com.niki914.uikit.infra.shape.G2FieldShape
import com.niki914.zafiro.app.R
import kotlinx.coroutines.launch

/**
 * 模型目录选择单（id-only，像素级复刻 bukit ChoiceBottomSheet 行）。
 * - 搜索框 + 名单；点一行即选中回填并收起；背景点击关闭；
 * - 选中态：单内字符串 == 输入框 trim。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelCatalogSheet(
    visible: Boolean,
    catalog: List<String>,
    currentModelInput: String,
    onDismissRequest: () -> Unit,
    onSelect: (String) -> Unit,
) {
    if (!visible) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    val trimmedCurrent = currentModelInput.trim()
    val filtered = rememberFilteredCatalog(catalog, query)

    /** 先走 M3 收起动画，再回调 onDismiss 移除组合，避免 sheet 闪现消失。 */
    fun hideThen(onHidden: () -> Unit) {
        scope.launch {
            sheetState.hide()
            onHidden()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            // sheet 高度锁在屏高 50%~60%：内容不足撑到 50%，超长列表封顶 60% 内滚动
            val screenH = LocalConfiguration.current.screenHeightDp.dp
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = screenH * 0.5f, max = screenH * 0.6f)
            ) {
                Text(
                    text = stringResource(R.string.ui_onboard_configure_model_catalog_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
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
                            CatalogRow(
                                modelId = modelId,
                                checked = modelId == trimmedCurrent,
                                onClick = { hideThen { onSelect(modelId) } },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 像素级复刻 bukit ChoiceRow：选中底 + G2 圆角 + 按压效果 + 行高。 */
@Composable
private fun CatalogRow(
    modelId: String,
    checked: Boolean,
    onClick: () -> Unit,
) {
    // 选中态 = 对比色底 + G2 圆角框，文字换 onContainer 保可读；未选中透明。
    // clip 保证按压底色与选中底色同形状（zafiro 侧 Surface 无 shape 参数）。
    val highlightShape = G2FieldShape(16.dp)
    SettingsItemSurface(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        minHeight = 64.dp,
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(highlightShape)
            .then(
                if (checked) Modifier.background(
                    MaterialTheme.colorScheme.primaryContainer,
                ) else Modifier,
            ),
    ) {
        CatalogRowContent(modelId = modelId, checked = checked)
    }
}

@Composable
private fun RowScope.CatalogRowContent(
    modelId: String,
    checked: Boolean,
) {
    Column(Modifier.weight(1f)) {
        Text(
            text = modelId,
            style = MaterialTheme.typography.bodyLarge,
            color = if (checked) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.alpha(1f),
        )
    }
    if (checked) {
        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.align(Alignment.CenterVertically),
        )
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
