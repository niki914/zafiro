package com.niki914.uikit.scroll

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.niki914.uikit.infra.scrollbar.LiquidOverscrollContainer
import com.niki914.uikit.infra.scrollbar.LiquidOverscrollController
import com.niki914.uikit.infra.scrollbar.rememberLiquidOverscrollController

typealias ZafiroOverscrollController = LiquidOverscrollController

@Composable
fun rememberZafiroOverscrollController(): ZafiroOverscrollController =
    rememberLiquidOverscrollController()

@Composable
fun ZafiroOverscrollContainer(
    overscrollController: ZafiroOverscrollController,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    LiquidOverscrollContainer(
        overscrollController = overscrollController,
        modifier = modifier,
        content = content,
    )
}
