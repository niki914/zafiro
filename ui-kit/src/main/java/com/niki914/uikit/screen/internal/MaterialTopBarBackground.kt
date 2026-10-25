package com.niki914.uikit.screen.internal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/**
 * Material Design (MD3 / MD3E) 顶栏容器背景。
 *
 * 纯 Compose 绘制，不使用 RuntimeShader，零 GPU FBO 离屏抓取开销。
 * 随滚动进度 [barAlpha] 平滑过渡 surfaceContainer 纯色。
 */
@Composable
fun MaterialTopBarBackground(
    chromeHeight: Dp,
    barAlpha: Float,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(chromeHeight)
            .background(containerColor.copy(alpha = barAlpha.coerceIn(0f, 1f))),
    )
}
