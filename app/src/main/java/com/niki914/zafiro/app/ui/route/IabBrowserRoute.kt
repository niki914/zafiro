package com.niki914.zafiro.app.ui.route

import androidx.compose.runtime.Composable
import com.niki914.zafiro.app.ui.content.IabBrowserContent

/**
 * IAB 浏览器页路由（薄壳，与 MarkdownScalePageRoute 同一模式：
 * 壳（LiquidScreen）由 ZafiroApp 按导航条目统一挂载，返回键由 LiquidScreen 统一处理，
 * 这里只提供页面内容）。
 */
@Composable
internal fun IabBrowserRoute() {
    IabBrowserContent()
}
