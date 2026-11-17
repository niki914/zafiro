package com.niki914.zafiro.app.ui.content

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.niki914.uikit.base.LocalThemeStyle
import com.niki914.uikit.base.ThemeStyle
import com.niki914.uikit.infra.component.OptionRow
import com.niki914.uikit.infra.component.OptionSheet
import com.niki914.uikit.infra.component.SheetIconButton
import com.niki914.uikit.textfield.ZafiroTextField
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.model.ModelCatalogFetchStatus
import com.niki914.zafiro.repo.AvailableModelItem

/**
 * 模型目录选择单（id-only）。
 * - 顶部工具栏：加号按钮（平滑收起后打开添加弹窗） + 刷新按钮（四态状态机，加载态不可打断） + 搜索框（右侧）；
 * - 列表：可用模型与云端模型；点一行即选中回填并收起；背景点击关闭；
 * - 选中态：单内字符串 == 输入框 trim。
 */
@Composable
fun ModelCatalogSheet(
    visible: Boolean,
    catalog: List<AvailableModelItem>,
    currentModelInput: String,
    onDismissRequest: () -> Unit,
    onSelect: (String) -> Unit,
    fetchStatus: ModelCatalogFetchStatus = ModelCatalogFetchStatus.Idle,
    onRefresh: () -> Unit = {},
    onRequestAddModel: () -> Unit = {},
) {
    // 提前返回是有意的：query 随组合一起销毁，关单再开时搜索框是干净的（原行为）。
    if (!visible) return
    var query by rememberSaveable { mutableStateOf("") }
    var dismissThenAction by remember { mutableStateOf<((() -> Unit) -> Unit)?>(null) }
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 20.dp, top = 4.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // 按钮 1：加号（点加号收起 Sheet，并在外层调起全屏居中输入框）
                SheetIconButton(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add",
                    onClick = {
                        val dismiss = dismissThenAction
                        if (dismiss != null) {
                            dismiss.invoke { onRequestAddModel() }
                        } else {
                            onDismissRequest()
                            onRequestAddModel()
                        }
                    },
                )

                // 按钮 2：刷新（带状态机：idle -> loading -> success / failure -> loading）
                val isLoading = fetchStatus == ModelCatalogFetchStatus.Loading
                SheetIconButton(
                    onClick = {
                        if (!isLoading) {
                            onRefresh()
                        }
                    },
                    enabled = !isLoading,
                ) {
                    when (fetchStatus) {
                        ModelCatalogFetchStatus.Idle -> {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh",
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        ModelCatalogFetchStatus.Loading -> {
                            CatalogRunningIndicator()
                        }
                        ModelCatalogFetchStatus.Success -> {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        ModelCatalogFetchStatus.Failure -> {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }

                // 右侧：搜索框
                ZafiroTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = stringResource(
                        R.string.ui_onboard_configure_model_catalog_search_placeholder
                    ),
                    singleLine = true,
                    maxLines = 1,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 4.dp),
                )
            }
        },
    ) { dismissThen ->
        dismissThenAction = dismissThen

        if (filtered.isEmpty()) {
            Text(
                text = stringResource(R.string.ui_onboard_configure_model_catalog_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
        } else {
            LazyColumn {
                items(filtered, key = { it.id }) { item ->
                    OptionRow(
                        title = item.id,
                        checked = item.id == trimmedCurrent,
                        onClick = { dismissThen { onSelect(item.id) } },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CatalogRunningIndicator() {
    val themeStyle = LocalThemeStyle.current
    val color = MaterialTheme.colorScheme.primary
    if (themeStyle == ThemeStyle.Material3Expressive) {
        LoadingIndicator(
            modifier = Modifier.size(24.dp),
            color = color,
        )
    } else {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            color = color,
            strokeWidth = 2.dp,
        )
    }
}

@Composable
private fun rememberFilteredCatalog(
    catalog: List<AvailableModelItem>,
    query: String,
): List<AvailableModelItem> {
    val q = query.trim().lowercase()
    if (q.isBlank()) return catalog
    return catalog.filter { it.id.lowercase().contains(q) }
}
