package com.niki914.uikit.scroll

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.niki914.uikit.base.LocalThemeStyle
import com.niki914.uikit.base.ThemeStyle
import com.niki914.uikit.infra.scrollbar.LiquidOverscrollContainer
import com.niki914.uikit.infra.scrollbar.LiquidOverscrollController
import com.niki914.uikit.infra.scrollbar.rememberLiquidOverscrollController

typealias ZafiroOverscrollController = LiquidOverscrollController

@Composable
fun rememberZafiroOverscrollController(): ZafiroOverscrollController =
    rememberLiquidOverscrollController()

/**
 * 统一超滚平移容器门面：
 * - [ThemeStyle.LiquidGlass]: 渲染为 iOS 刚体平移与弹簧阻尼超滚（[LiquidOverscrollContainer]）；
 * - [ThemeStyle.Material3] / [ThemeStyle.Material3Expressive]: 渲染为纯容器，不屏蔽 [LocalOverscrollFactory]，
 *   保留 Android 系统原生的拉伸弹性张力（Stretch Overscroll）。
 */
@Composable
fun ZafiroOverscrollContainer(
    overscrollController: ZafiroOverscrollController,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    when (LocalThemeStyle.current) {
        ThemeStyle.LiquidGlass -> {
            LiquidOverscrollContainer(
                overscrollController = overscrollController,
                modifier = modifier,
                content = content,
            )
        }

        ThemeStyle.Material3,
        ThemeStyle.Material3Expressive -> {
            Box(
                modifier = modifier.fillMaxSize(),
                content = content,
            )
        }
    }
}
