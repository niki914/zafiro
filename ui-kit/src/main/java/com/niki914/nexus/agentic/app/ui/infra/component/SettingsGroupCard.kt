package com.niki914.nexus.agentic.app.ui.infra.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.niki914.nexus.agentic.app.ui.infra.shape.G2CardShape

/**
 * iOS 风分组卡片：外置章节标题 + 圆角卡片容器。
 * 排版抄自 ui/settings/StyledToggle 的 independent=true 分支，颜色走 MaterialTheme，
 * 深浅色由 colorScheme.surfaceContainer / onSurfaceVariant 自动适配。
 *
 * 卡片本身 clip 成圆角，方便子项（如 `SettingNavigationItem`）在按压渐变时
 * 不会溢出圆角边界。
 */
@Composable
fun SettingsGroupCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val titleColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
    val cardColor = MaterialTheme.colorScheme.surfaceContainer
    val cardShape = G2CardShape(28.dp)

    Column(modifier = modifier.fillMaxWidth()) {
        if (!title.isNullOrBlank()) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = titleColor,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(cardShape)
                .background(cardColor, cardShape),
            content = content,
        )
    }
}
