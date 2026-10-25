package com.niki914.zafiro.app.ui.preview.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.niki914.uikit.base.BaseTheme
import com.niki914.uikit.base.ThemeStyle
import com.niki914.uikit.button.ZafiroButton
import com.niki914.uikit.button.ZafiroScrollToBottomButton
import com.niki914.uikit.button.ZafiroTonalButton

@Composable
private fun ButtonThemeSample(title: String) {
    var isLoading by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 1. 常规全宽操作按钮（主操作 + 次操作）
            Text(
                text = "1. 标准通栏主次操作按钮",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            ZafiroButton(
                text = "保存并应用配置",
                onClick = {},
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                },
            )
            ZafiroTonalButton(
                text = "重置为默认值",
                onClick = {},
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                },
            )

            // 2. 双按钮对称布局（模拟详情页或弹窗底栏）
            Text(
                text = "2. 对称双操作槽位",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ZafiroTonalButton(
                    text = "取消",
                    onClick = {},
                    modifier = Modifier.weight(1f),
                )
                ZafiroButton(
                    text = "确认提交",
                    onClick = {},
                    modifier = Modifier.weight(1f),
                )
            }

            // 3. 状态演示：加载中与禁用态
            Text(
                text = "3. 状态演示（加载中与禁用）",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ZafiroButton(
                    text = "提交中",
                    isLoading = true,
                    onClick = {},
                    modifier = Modifier.weight(1f),
                )
                ZafiroButton(
                    text = "不可用状态",
                    enabled = false,
                    onClick = {},
                    modifier = Modifier.weight(1f),
                )
            }

            // 4. 回到底部悬浮 FAB
            Text(
                text = "4. 回到底部快捷悬浮按钮（ScrollToBottom FAB）",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZafiroScrollToBottomButton(
                    visible = true,
                    onClick = {},
                )
                Text(
                    text = "向上滑动聊天记录时在右下角悬浮展开",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Preview(
    name = "1. Liquid Glass - Buttons",
    showBackground = true,
    widthDp = 412,
    heightDp = 640,
)
@Composable
fun ButtonLiquidPreview() {
    BaseTheme(themeStyle = ThemeStyle.LiquidGlass) {
        ButtonThemeSample(title = "Liquid Glass 模式：毛玻璃光泽底座 + 阻尼拖拽弹性形变")
    }
}

@Preview(
    name = "2. Material 3 (Classic) - Buttons",
    showBackground = true,
    widthDp = 412,
    heightDp = 640,
)
@Composable
fun ButtonMaterial3Preview() {
    BaseTheme(themeStyle = ThemeStyle.Material3) {
        ButtonThemeSample(title = "Material 3 模式：原生实心胶囊底座 + 水波纹 + 零额外 GPU 开销")
    }
}

@Preview(
    name = "3. Material 3 Expressive - Buttons",
    showBackground = true,
    widthDp = 412,
    heightDp = 640,
)
@Composable
fun ButtonMaterial3ExpressivePreview() {
    BaseTheme(themeStyle = ThemeStyle.Material3Expressive) {
        ButtonThemeSample(title = "Material 3 Expressive 模式：Squircle 表现力圆角 + 物理弹簧挤压 (springSquish)")
    }
}
