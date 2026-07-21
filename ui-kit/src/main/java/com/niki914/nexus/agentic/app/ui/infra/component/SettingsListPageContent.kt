package com.niki914.nexus.agentic.app.ui.infra.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.niki914.nexus.agentic.app.ui.infra.liquidScreenHazeSource
import com.niki914.nexus.agentic.app.ui.infra.liquidScreenTopPadding

/**
 * 设置列表页容器，必须运行在 `LiquidScreen` 内容树内。
 *
 * Preview 或独立样例请用 `ProvideLiquidScreenContentForPreview` 提供壳层上下文。
 */
@Composable
fun SettingsListPageContent(
    description: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .liquidScreenHazeSource()
            .verticalScroll(rememberScrollState())
            .padding(top = liquidScreenTopPadding())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (!description.isNullOrBlank()) {
            PageDescriptionText(text = description)
        }
        content()
    }
}
