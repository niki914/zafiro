package com.niki914.uikit.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.niki914.uikit.infra.LocalScreenBackdrop

/**
 * 局部 Backdrop 隔离容器。
 *
 * 为页面内部的底层内容与浮动层建立独立的局部 Backdrop 隔离上下文：
 * 仅捕获底层内容并通过 [LocalScreenBackdrop] 局域提供给内部浮层组件采样，
 * 避免浮层与外层全屏容器形成祖先环形依赖。
 */
@Composable
fun ZafiroLocalBackdropContainer(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.(contentBackdropModifier: Modifier) -> Unit,
) {
    val localBackdrop = rememberLayerBackdrop()
    val contentBackdropModifier = remember(localBackdrop) {
        Modifier.layerBackdrop(localBackdrop)
    }
    CompositionLocalProvider(
        LocalScreenBackdrop provides localBackdrop,
    ) {
        Box(modifier = modifier) {
            content(contentBackdropModifier)
        }
    }
}
