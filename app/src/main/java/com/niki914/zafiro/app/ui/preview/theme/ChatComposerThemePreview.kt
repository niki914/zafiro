package com.niki914.zafiro.app.ui.preview.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.niki914.zafiro.app.ui.content.composer.ZafiroChatComposer

@Composable
private fun ChatComposerSample(
    title: String,
    isGenerating: Boolean = false,
    contextProgress: Float? = 0.42f,
) {
    var text by remember { mutableStateOf("你好，帮我用 Kotlin 写一个协程并发任务") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            ZafiroChatComposer(
                value = text,
                onValueChange = { text = it },
                onSendClick = {},
                onStopClick = {},
                isGenerating = isGenerating,
                contextProgress = contextProgress,
                onAttachImageClick = {},
            )
        }
    }
}

@Preview(
    name = "1. Liquid Glass",
    showBackground = true,
    widthDp = 412,
    heightDp = 240,
)
@Composable
fun ChatComposerLiquidPreview() {
    BaseTheme(themeStyle = ThemeStyle.LiquidGlass) {
        ChatComposerSample(title = "Liquid Glass 模式：毛玻璃容器 + 圆形气泡底座")
    }
}

@Preview(
    name = "2. Material 3 (Classic)",
    showBackground = true,
    widthDp = 412,
    heightDp = 240,
)
@Composable
fun ChatComposerMaterial3Preview() {
    BaseTheme(themeStyle = ThemeStyle.Material3) {
        ChatComposerSample(title = "Material 3 模式：胶囊实体容器 + 微深圆底按钮 + 独立上下文环")
    }
}

@Preview(
    name = "3. Material 3 Expressive",
    showBackground = true,
    widthDp = 412,
    heightDp = 240,
)
@Composable
fun ChatComposerMaterial3ExpressivePreview() {
    BaseTheme(themeStyle = ThemeStyle.Material3Expressive) {
        ChatComposerSample(title = "Material 3 Expressive 模式：Squircle 连续圆角 + Spring 弹性触感 + LoadingIndicator")
    }
}

@Preview(
    name = "4. Material 3 Expressive - Generating",
    showBackground = true,
    widthDp = 412,
    heightDp = 240,
)
@Composable
fun ChatComposerM3EGeneratingPreview() {
    BaseTheme(themeStyle = ThemeStyle.Material3Expressive) {
        ChatComposerSample(
            title = "MD3E 生成态：LoadingIndicator 花瓣动态形变",
            isGenerating = true,
        )
    }
}
