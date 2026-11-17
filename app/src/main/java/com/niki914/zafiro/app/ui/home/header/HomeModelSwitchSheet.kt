package com.niki914.zafiro.app.ui.home.header

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.niki914.okia.message.ThinkingLevel
import com.niki914.uikit.infra.component.OptionRow
import com.niki914.uikit.infra.component.OptionSheet
import com.niki914.uikit.infra.component.ZafiroChip
import com.niki914.uikit.textfield.ZafiroTextField
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.model.SavedConfigSummary
import com.niki914.zafiro.app.ui.model.THINKING_LEVEL_OPTIONS

@Composable
internal fun HomeModelSwitchSheet(
    visible: Boolean,
    savedConfigs: List<SavedConfigSummary>,
    activeConfig: SavedConfigSummary?,
    onDismissRequest: () -> Unit,
    onSelectConfig: (String) -> Unit,
    onSelectModel: (configId: String, modelId: String) -> Unit,
    onUpdateThinkingLevel: (configId: String, wireValue: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return

    var searchQuery by remember { mutableStateOf("") }
    LaunchedEffect(activeConfig?.id) {
        searchQuery = ""
    }

    val screenH = LocalConfiguration.current.screenHeightDp.dp
    val currentModels = activeConfig?.availableModels.orEmpty()

    val filteredModels = remember(currentModels, searchQuery) {
        val q = searchQuery.trim().lowercase()
        val list = if (q.isBlank()) currentModels
        else currentModels.filter { it.id.lowercase().contains(q) }
        list.distinctBy { it.id }
    }

    val currentThinkingWire = activeConfig?.thinkingLevel.orEmpty()

    OptionSheet(
        visible = true,
        onDismissRequest = onDismissRequest,
        title = stringResource(R.string.ui_home_model_sheet_title),
        bodyModifier = Modifier.heightIn(min = screenH * 0.45f, max = screenH * 0.7f),
        modifier = modifier,
        header = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 分类 1：配置
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.ui_home_model_sheet_section_config),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        items(savedConfigs, key = { it.id }) { config ->
                            ZafiroChip(
                                selected = config.id == activeConfig?.id,
                                onClick = { onSelectConfig(config.id) },
                                label = config.name,
                            )
                        }
                    }
                }

                // 分类 2：思考强度
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.ui_home_model_sheet_section_thinking),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        items(THINKING_LEVEL_OPTIONS, key = { it.wire }) { item ->
                            val isSelected = when (item.wire) {
                                "" -> currentThinkingWire.isBlank()
                                ThinkingLevel.OFF.wireValue -> currentThinkingWire == "none" || currentThinkingWire == "off"
                                else -> item.wire.equals(currentThinkingWire, ignoreCase = true)
                            }
                            ZafiroChip(
                                selected = isSelected,
                                onClick = {
                                    activeConfig?.let { cfg ->
                                        onUpdateThinkingLevel(cfg.id, item.wire)
                                    }
                                },
                                label = item.label,
                            )
                        }
                    }
                }

                // 分类 3：模型
                Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    Text(
                        text = stringResource(R.string.ui_home_model_sheet_section_model),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                    ZafiroTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = stringResource(R.string.ui_onboard_configure_model_catalog_search_placeholder),
                        singleLine = true,
                        maxLines = 1,
                        minHeight = 36.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                    )
                }
            }
        },
    ) { _ ->
        // 主内容区：当前配置对应的可选模型列表（点击切换打勾，绝不自动收起 Sheet）
        if (filteredModels.isEmpty()) {
            Text(
                text = stringResource(R.string.ui_onboard_configure_model_catalog_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(filteredModels, key = { it.id }) { modelItem ->
                    val isChecked = modelItem.id == activeConfig?.modelId
                    OptionRow(
                        title = modelItem.id,
                        checked = isChecked,
                        onClick = {
                            activeConfig?.let { cfg ->
                                onSelectModel(cfg.id, modelItem.id)
                            }
                        },
                    )
                }
            }
        }
    }
}
