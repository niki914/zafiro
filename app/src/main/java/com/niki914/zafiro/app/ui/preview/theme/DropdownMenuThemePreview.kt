package com.niki914.zafiro.app.ui.preview.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.niki914.uikit.base.BaseTheme
import com.niki914.uikit.base.ThemeStyle
import com.niki914.uikit.menu.ZafiroDropdownMenu
import com.niki914.uikit.menu.ZafiroMenuAlignment
import com.niki914.uikit.menu.ZafiroMenuItem

@Composable
private fun DropdownMenuPreviewContent(label: String) {
    var expanded by remember { mutableStateOf(true) }
    var anchorBounds by remember { mutableStateOf<Rect?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp),
                contentAlignment = Alignment.TopEnd,
            ) {
                IconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .onGloballyPositioned { coordinates ->
                            anchorBounds = coordinates.boundsInRoot()
                        },
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Menu Trigger",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }

                ZafiroDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    anchorBounds = anchorBounds ?: Rect(260f, 0f, 380f, 48f),
                    alignment = ZafiroMenuAlignment.BottomEnd,
                ) {
                    ZafiroMenuItem(
                        title = "Claude 3.5 Sonnet",
                        trailingText = "anthropic",
                        checked = true,
                        onClick = { expanded = false },
                    )
                    ZafiroMenuItem(
                        title = "Gemini 2.5 Flash",
                        trailingText = "google",
                        checked = false,
                        onClick = { expanded = false },
                    )
                    ZafiroMenuItem(
                        title = "新建对话",
                        onClick = { expanded = false },
                    )
                    ZafiroMenuItem(
                        title = "设置",
                        onClick = { expanded = false },
                    )
                }
            }
        }
    }
}

@Preview(
    name = "1. Liquid Glass - Dropdown Menu",
    showBackground = true,
    widthDp = 412,
    heightDp = 380,
)
@Composable
fun DropdownMenuLiquidPreview() {
    BaseTheme(
        darkTheme = false,
        dynamicColor = false,
        themeStyle = ThemeStyle.LiquidGlass,
    ) {
        DropdownMenuPreviewContent("Liquid Glass 模式：毛玻璃与变形动画")
    }
}

@Preview(
    name = "2. Material 3 - Dropdown Menu",
    showBackground = true,
    widthDp = 412,
    heightDp = 380,
)
@Composable
fun DropdownMenuMaterialPreview() {
    BaseTheme(
        darkTheme = false,
        dynamicColor = false,
        themeStyle = ThemeStyle.Material3,
    ) {
        DropdownMenuPreviewContent("Material 3 模式：标准实体卡片与统一按压反馈")
    }
}

@Preview(
    name = "3. Material 3 Expressive - Dropdown Menu",
    showBackground = true,
    widthDp = 412,
    heightDp = 380,
)
@Composable
fun DropdownMenuExpressivePreview() {
    BaseTheme(
        darkTheme = false,
        dynamicColor = false,
        themeStyle = ThemeStyle.Material3Expressive,
    ) {
        DropdownMenuPreviewContent("Material 3 Expressive 模式：大圆角与激活胶囊药丸")
    }
}
