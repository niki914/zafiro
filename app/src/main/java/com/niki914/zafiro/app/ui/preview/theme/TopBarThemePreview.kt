package com.niki914.zafiro.app.ui.preview.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.niki914.uikit.base.BaseTheme
import com.niki914.uikit.base.LocalThemeStyle
import com.niki914.uikit.base.ThemeStyle
import com.niki914.uikit.infra.internal.LiquidTopBarBackground
import com.niki914.uikit.screen.TitleDirection
import com.niki914.uikit.screen.ZafiroScreenTitle
import com.niki914.uikit.screen.ZafiroScreenTitleContent
import com.niki914.uikit.screen.ZafiroScreenTitleText
import com.niki914.uikit.screen.ZafiroTopBar
import com.niki914.uikit.screen.ZafiroTopBarDefaults
import com.niki914.uikit.screen.internal.MaterialTopBarBackground

/**
 * 模拟列表卡片内容，作为顶栏下方的衬底，以便直观对比顶栏与按钮的半透明/实体底色差异。
 */
@Composable
private fun SampleBackgroundContent() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 72.dp),
    ) {
        repeat(5) { index ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            ) {
                Text(
                    text = "页面衬底内容卡片 #$index",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/**
 * 模拟主对话界面的模型切换胶囊，验证居中对齐与视觉平衡。
 */
@Composable
private fun PreviewHomeTitleChip(modelName: String = "Gemini 2.5 Flash") {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable { }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = modelName,
            style = TextStyle(
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(
            imageVector = Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun TopBarThemeContainer(
    title: ZafiroScreenTitle,
    showLeftButton: Boolean = true,
    showRightButton: Boolean = true,
    leftButton: (@Composable () -> Unit)? = null,
    rightButton: (@Composable () -> Unit)? = null,
) {
    val barHeight = ZafiroTopBarDefaults.ButtonSlotHeight
    val themeStyle = LocalThemeStyle.current
    val isLiquid = themeStyle == ThemeStyle.LiquidGlass

    val contentBackdrop = rememberLayerBackdrop()
    val chromeBackdrop = rememberLayerBackdrop()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (isLiquid) Modifier.layerBackdrop(contentBackdrop) else Modifier
                ),
        ) {
            SampleBackgroundContent()
        }

        // Layer 2: Action bar background (毛玻璃/纯实体底色自适应过渡)
        if (isLiquid) {
            LiquidTopBarBackground(
                contentBackdrop = contentBackdrop,
                chromeBackdrop = chromeBackdrop,
                chromeHeight = barHeight,
                buttonSlotHeight = barHeight,
                topInset = 0.dp,
                barAlpha = 1f,
            )
        } else {
            MaterialTopBarBackground(
                chromeHeight = barHeight,
                barAlpha = 1f,
            )
        }

        // Layer 3: Action bar foreground (全局恒定三槽骨架)
        ZafiroTopBar(
            title = title,
            titleDirection = TitleDirection.Forward,
            baseTitleAlpha = 1f,
            topInset = 0.dp,
            backdrop = if (isLiquid) contentBackdrop else emptyBackdrop(),
            showLeftButton = showLeftButton,
            showRightButton = showRightButton,
            leftButton = leftButton,
            rightButton = rightButton,
            onLeftClick = {},
            onRightClick = {},
        )
    }
}

@Preview(
    name = "1. Liquid Glass - TopBar & Buttons",
    showBackground = true,
    widthDp = 412,
    heightDp = 360,
)
@Composable
fun TopBarLiquidPreview() {
    BaseTheme(themeStyle = ThemeStyle.LiquidGlass) {
        TopBarThemeContainer(
            title = ZafiroScreenTitleText("Liquid Glass 标题栏"),
            leftButton = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                )
            },
            rightButton = {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "More",
                )
            },
        )
    }
}

@Preview(
    name = "2. Material 3 - Home Chat TopBar (Centered Chip)",
    showBackground = true,
    widthDp = 412,
    heightDp = 360,
)
@Composable
fun TopBarMaterial3HomeChatPreview() {
    BaseTheme(themeStyle = ThemeStyle.Material3) {
        TopBarThemeContainer(
            title = ZafiroScreenTitleContent(content = { PreviewHomeTitleChip() }),
            leftButton = {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = "History",
                )
            },
            rightButton = {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "More",
                )
            },
        )
    }
}

@Preview(
    name = "3. Material 3 - Settings (No Right Button)",
    showBackground = true,
    widthDp = 412,
    heightDp = 360,
)
@Composable
fun TopBarMaterial3SettingsPreview() {
    BaseTheme(themeStyle = ThemeStyle.Material3) {
        TopBarThemeContainer(
            title = ZafiroScreenTitleText("Appearance & Language"),
            showRightButton = false,
            leftButton = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                )
            },
            rightButton = null,
        )
    }
}

@Preview(
    name = "4. Material 3 Expressive - TopBar & Buttons",
    showBackground = true,
    widthDp = 412,
    heightDp = 360,
)
@Composable
fun TopBarMaterial3ExpressivePreview() {
    BaseTheme(themeStyle = ThemeStyle.Material3Expressive) {
        TopBarThemeContainer(
            title = ZafiroScreenTitleText("Material 3 Expressive"),
            leftButton = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                )
            },
            rightButton = {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "More",
                )
            },
        )
    }
}
