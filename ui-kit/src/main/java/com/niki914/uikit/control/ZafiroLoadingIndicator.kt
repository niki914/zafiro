package com.niki914.uikit.control

import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.niki914.uikit.base.ThemeStyle
import com.niki914.uikit.base.UiStylePart
import com.niki914.uikit.base.resolvedUiStyle

/**
 * 加载指示。表现力风格用花瓣，其余用圆环。风格只看加载这一项。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ZafiroLoadingIndicator(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    size: Dp = 36.dp,
    petalSize: Dp = size,
    strokeWidth: Dp = 3.dp,
) {
    if (resolvedUiStyle(UiStylePart.Loading) == ThemeStyle.Material3Expressive) {
        LoadingIndicator(
            modifier = modifier.size(petalSize),
            color = color,
        )
    } else {
        CircularProgressIndicator(
            modifier = modifier.size(size),
            color = color,
            strokeWidth = strokeWidth,
        )
    }
}
