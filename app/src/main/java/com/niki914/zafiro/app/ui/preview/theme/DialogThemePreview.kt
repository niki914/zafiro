package com.niki914.zafiro.app.ui.preview.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.niki914.uikit.base.BaseTheme
import com.niki914.uikit.base.ThemeStyle
import com.niki914.uikit.dialog.ZafiroConfirmationDialog
import com.niki914.uikit.dialog.ZafiroInputDialog
import com.niki914.uikit.dialog.ZafiroSingleChoiceDialog
import com.niki914.uikit.infra.LiquidScreen
import com.niki914.uikit.infra.nav.TitleBarScrollState
import com.niki914.uikit.infra.rememberLiquidScreenState

@Composable
private fun DialogThemeSample(
    title: String,
    message: String,
) {
    val screenState = rememberLiquidScreenState(
        showLeftButton = false,
        showRightButton = false,
        showBlurLayer = false,
    )

    LiquidScreen(
        state = screenState,
        titleBarScroll = remember { TitleBarScrollState() },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            ZafiroConfirmationDialog(
                visible = true,
                onDismissRequest = {},
                title = title,
                text = message,
                negativeButtonText = "取消",
                positiveButtonText = "确认重置",
                onNegativeClick = {},
                onPositiveClick = {},
            )
        }
    }
}

@Composable
private fun SingleChoiceDialogThemeSample() {
    val screenState = rememberLiquidScreenState(
        showLeftButton = false,
        showRightButton = false,
        showBlurLayer = false,
    )

    LiquidScreen(
        state = screenState,
        titleBarScroll = remember { TitleBarScrollState() },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            ZafiroSingleChoiceDialog(
                visible = true,
                onDismissRequest = {},
                title = "协议",
                hint = "切换协议后，请确认端点与协议匹配。",
                options = listOf("openai-responses", "openai-chat-completions", "anthropic"),
                selectedId = "openai-responses",
                optionId = { it },
                optionLabel = { it },
                onSelect = {},
                cancelButtonText = "取消",
            )
        }
    }
}

@Composable
private fun InputDialogThemeSample() {
    val screenState = rememberLiquidScreenState(
        showLeftButton = false,
        showRightButton = false,
        showBlurLayer = false,
    )

    LiquidScreen(
        state = screenState,
        titleBarScroll = remember { TitleBarScrollState() },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            ZafiroInputDialog(
                visible = true,
                onDismissRequest = {},
                title = "新增记忆",
                hint = "输入内容",
                value = "",
                onValueChange = {},
                placeholder = "例如：用户的生日是 X 月 Y 日",
                positiveButtonText = "保存记忆",
                negativeButtonText = "取消",
                onPositiveClick = {},
                onNegativeClick = {},
                singleLine = false,
                maxLines = 5,
            )
        }
    }
}

@Preview(
    name = "1. Liquid Glass - Confirmation",
    showBackground = true,
    widthDp = 412,
    heightDp = 500,
)
@Composable
fun DialogLiquidPreview() {
    BaseTheme(themeStyle = ThemeStyle.LiquidGlass) {
        DialogThemeSample(
            title = "重置应用配置",
            message = "确定要将所有模型、Prompt 和外观设置恢复为出厂默认值吗？该操作不可撤销。",
        )
    }
}

@Preview(
    name = "2. Material 3 - Confirmation",
    showBackground = true,
    widthDp = 412,
    heightDp = 500,
)
@Composable
fun DialogMaterial3Preview() {
    BaseTheme(themeStyle = ThemeStyle.Material3) {
        DialogThemeSample(
            title = "重置应用配置",
            message = "确定要将所有模型、Prompt 和外观设置恢复为出厂默认值吗？该操作不可撤销。",
        )
    }
}

@Preview(
    name = "3. Material 3 Expressive - Confirmation",
    showBackground = true,
    widthDp = 412,
    heightDp = 500,
)
@Composable
fun DialogMaterial3ExpressivePreview() {
    BaseTheme(themeStyle = ThemeStyle.Material3Expressive) {
        DialogThemeSample(
            title = "重置应用配置",
            message = "确定要将所有模型、Prompt 和外观设置恢复为出厂默认值吗？该操作不可撤销。",
        )
    }
}

@Preview(
    name = "4. Liquid Glass - Protocol Single Choice",
    showBackground = true,
    widthDp = 412,
    heightDp = 500,
)
@Composable
fun SingleChoiceDialogLiquidPreview() {
    BaseTheme(themeStyle = ThemeStyle.LiquidGlass) {
        SingleChoiceDialogThemeSample()
    }
}

@Preview(
    name = "5. Material 3 - Protocol Single Choice",
    showBackground = true,
    widthDp = 412,
    heightDp = 500,
)
@Composable
fun SingleChoiceDialogMaterial3Preview() {
    BaseTheme(themeStyle = ThemeStyle.Material3) {
        SingleChoiceDialogThemeSample()
    }
}

@Preview(
    name = "6. Material 3 Expressive - Protocol Single Choice",
    showBackground = true,
    widthDp = 412,
    heightDp = 500,
)
@Composable
fun SingleChoiceDialogMaterial3ExpressivePreview() {
    BaseTheme(themeStyle = ThemeStyle.Material3Expressive) {
        SingleChoiceDialogThemeSample()
    }
}

@Preview(
    name = "7. Liquid Glass - Memory Input",
    showBackground = true,
    widthDp = 412,
    heightDp = 500,
)
@Composable
fun InputDialogLiquidPreview() {
    BaseTheme(themeStyle = ThemeStyle.LiquidGlass) {
        InputDialogThemeSample()
    }
}

@Preview(
    name = "8. Material 3 - Memory Input",
    showBackground = true,
    widthDp = 412,
    heightDp = 500,
)
@Composable
fun InputDialogMaterial3Preview() {
    BaseTheme(themeStyle = ThemeStyle.Material3) {
        InputDialogThemeSample()
    }
}

@Preview(
    name = "9. Material 3 Expressive - Memory Input",
    showBackground = true,
    widthDp = 412,
    heightDp = 500,
)
@Composable
fun InputDialogMaterial3ExpressivePreview() {
    BaseTheme(themeStyle = ThemeStyle.Material3Expressive) {
        InputDialogThemeSample()
    }
}
