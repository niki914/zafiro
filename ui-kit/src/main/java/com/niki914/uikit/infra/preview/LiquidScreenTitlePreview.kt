package com.niki914.uikit.infra.preview

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.niki914.uikit.base.BaseTheme
import com.niki914.uikit.infra.LiquidScreen
import com.niki914.uikit.infra.LiquidScreenTitle
import com.niki914.uikit.infra.nav.TitleBarScrollState
import com.niki914.uikit.infra.rememberLiquidScreenState

/**
 * 顶栏标题的两种形态：`Text`（页面标识，壳层默认）与 `Content`（自定义 composable，
 * 样例用配置切换器）。切页动画与内容形态无关：同一条目内的标题变化一律瞬切，
 * 只有换条目才按方向左右滑 —— 那部分看不了静态 preview，只能手验。
 */
@Composable
private fun LiquidScreenTitlePreviewContent(custom: Boolean) {
    // 实例必须 remember 住：壳层以实例相等判断标题是否变化。
    val chip: @Composable () -> Unit = remember { { ActiveConfigChipSample() } }
    val screenState = rememberLiquidScreenState(
        title = if (custom) {
            LiquidScreenTitle.Content(chip)
        } else {
            LiquidScreenTitle.Text("Zafiro")
        },
        showLeftButton = false,
    )

    LiquidScreen(
        state = screenState,
        titleBarScroll = remember { TitleBarScrollState() },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        )
    }
}

/** 样例：配置切换器长什么样。真正的实现放业务侧，不在这。 */
@Composable
private fun ActiveConfigChipSample() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = "Claude Sonnet",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            fontSize = 20.sp,
        )
        Icon(
            imageVector = Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Preview(name = "Title Text", showBackground = true, widthDp = 420, heightDp = 240)
@Composable
private fun LiquidScreenTitleTextPreview() {
    BaseTheme(darkTheme = false, dynamicColor = false) {
        Surface { LiquidScreenTitlePreviewContent(custom = false) }
    }
}

@Preview(name = "Title Content Light", showBackground = true, widthDp = 420, heightDp = 240)
@Composable
private fun LiquidScreenTitleContentLightPreview() {
    BaseTheme(darkTheme = false, dynamicColor = false) {
        Surface { LiquidScreenTitlePreviewContent(custom = true) }
    }
}

@Preview(
    name = "Title Content Dark",
    showBackground = true,
    widthDp = 420,
    heightDp = 240,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun LiquidScreenTitleContentDarkPreview() {
    BaseTheme(darkTheme = true, dynamicColor = false) {
        Surface { LiquidScreenTitlePreviewContent(custom = true) }
    }
}
